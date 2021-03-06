package com.twitter.finagle.zipkin.thrift

import com.twitter.conversions.StorageUnitOps._
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Service, SimpleFilter, Thrift}
import com.twitter.finagle.service.{
  ResponseClassifier,
  ResponseClass,
  RetryBudget,
  RetryFilter,
  RetryPolicy,
  ReqRep,
  RequeueFilter,
  StatsFilter
}
import com.twitter.finagle.stats.{DenylistStatsReceiver, DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.thrift.Protocols
import com.twitter.finagle.thrift.scribe.thriftscala.{LogEntry, ResultCode, Scribe}
import com.twitter.finagle.tracing.{NullTracer, TracelessFilter}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.{host => Host}
import com.twitter.finagle.zipkin.core.{RawZipkinTracer, Span, TracerCache}
import com.twitter.scrooge.TReusableMemoryTransport
import com.twitter.util._
import java.nio.charset.StandardCharsets
import java.util.{Arrays, Base64}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import org.apache.thrift.TByteArrayOutputStream
import org.apache.thrift.protocol.TProtocol
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

object ScribeRawZipkinTracer {
  private[this] val tracerCache = new TracerCache[ScribeRawZipkinTracer]
  private[this] val label = "zipkin-scribe"

  private class ScribeMetricsFilter(stats: ScribeStats)
      extends SimpleFilter[Scribe.Log.Args, Scribe.Log.SuccessType] {
    def apply(
      req: Scribe.Log.Args,
      svc: Service[Scribe.Log.Args, Scribe.Log.SuccessType]
    ): Future[Scribe.Log.SuccessType] =
      svc(req).respond(stats.respond)
  }

  // only report these finagle metrics (including counters for individual exceptions)
  private[this] def filteredStatsReceiver(sr: StatsReceiver): StatsReceiver =
    new DenylistStatsReceiver(
      sr,
      {
        // MethodBuilder StatsFilter & RetryFilter
        case Seq(_, "logical", _*) => false
        case Seq(_, "retries", _*) => false

        // Basic Finagle stats
        case Seq(_, "requests") => false
        case Seq(_, "success") => false
        case Seq(_, "pending") => false
        case Seq(_, "failures", _*) => false

        case _ => true
      })

  // we must use a response classifer as the finagle Thrift client deals with raw
  // bytes and not the scrooge generated types.
  private[finagle] val responseClassifier: ResponseClassifier = {
    case ReqRep(_, Return(ResultCode.TryLater)) => ResponseClass.RetryableFailure
    case ReqRep(_, Return(_: ResultCode.EnumUnknownResultCode)) => ResponseClass.NonRetryableFailure
  }

  private[this] val shouldRetry: PartialFunction[
    (Scribe.Log.Args, Try[Scribe.Log.SuccessType]),
    Boolean
  ] = {
    // We don't retry failures that the RequeueFilter will handle
    case (_, Throw(RequeueFilter.Requeueable(_))) => false
    case tup if responseClassifier.isDefinedAt(ReqRep(tup._1, tup._2)) =>
      responseClassifier(ReqRep(tup._1, tup._2)) match {
        case ResponseClass.Failed(retryable) => retryable
        case _ => false
      }
  }

  private[this] val retryPolicy = RetryPolicy.tries(
    numTries = 3, // MethodBuilder default
    shouldRetry = shouldRetry
  )

  // exposed for testing
  private[thrift] def newClient(
    scribeHost: String,
    scribePort: Int,
    scribeStats: ScribeStats,
    clientName: String,
    clientStatsReceiver: StatsReceiver,
    scribeLogSvc: Option[Service[Scribe.Log.Args, ResultCode]] = None
  ): Scribe.MethodPerEndpoint = {
    val scopedStatsReceiver = filteredStatsReceiver(clientStatsReceiver).scope(clientName)
    val logicalStatsReceiver = scopedStatsReceiver.scope("logical")

    // share the retryBudget between the retry and requeue filter
    val retryBudget = RetryBudget()
    val retryFilter = new RetryFilter(
      retryPolicy = retryPolicy,
      retryBudget = retryBudget,
      timer = DefaultTimer,
      statsReceiver = scopedStatsReceiver
    )

    val statsFilter = StatsFilter.typeAgnostic(
      logicalStatsReceiver,
      responseClassifier,
      StatsFilter.DefaultExceptions,
      TimeUnit.MILLISECONDS
    )

    val transport = scribeLogSvc match {
      case Some(logSvc) => Scribe.ServicePerEndpoint(log = logSvc)
      case None =>
        Thrift.client
          .withRetryBudget(retryBudget)
          .withSessionPool.maxSize(5)
          .withSessionPool.maxWaiters(250)
          .withSessionQualifier.noFailFast
          .withSessionQualifier.noFailureAccrual
          .withStatsReceiver(scopedStatsReceiver)
          .withTracer(NullTracer)
          .withRequestTimeout(1.second) // each "logical" retry will have this timeout
          .servicePerEndpoint[Scribe.ServicePerEndpoint](
            s"inet!$scribeHost:$scribePort",
            clientName)
    }

    val tracelessFilter = new TracelessFilter
    val scribeMetricsFilter = new ScribeMetricsFilter(scribeStats)
    val filteredTransport =
      transport.withLog(
        log = tracelessFilter
          .andThen(statsFilter)
          .andThen(retryFilter)
          // This is placed after the retry filter so
          // that we update stats on retried requests
          .andThen(scribeMetricsFilter)
          .andThen(transport.log)
      )

    Thrift.Client.methodPerEndpoint(filteredTransport)
  }

  /**
   * Creates a [[com.twitter.finagle.tracing.Tracer]] that sends traces to Zipkin via scribe.
   *
   * @param scribeHost Host to send trace data to
   * @param scribePort Port to send trace data to
   * @param scribeCategory scribe category under which traces will be logged
   * @param statsReceiver Where to log information about tracing success/failures
   * @param label label to use for Scribe stats and the associated Finagle client
   * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
   */
  def apply(
    scribeHost: String = Host().getHostName,
    scribePort: Int = Host().getPort,
    scribeCategory: String = "zipkin",
    statsReceiver: StatsReceiver = DefaultStatsReceiver,
    label: String = label,
    timer: Timer = DefaultTimer
  ): ScribeRawZipkinTracer = {
    val scribeStats = new ScribeStats(statsReceiver.scope(label))
    tracerCache.getOrElseUpdate(
      scribeHost + scribePort + scribeCategory,
      apply(
        newClient(scribeHost, scribePort, scribeStats, label, statsReceiver.scope("clnt")),
        scribeCategory,
        statsReceiver,
        timer
      )
    )
  }

  /**
   * Creates a [[com.twitter.finagle.tracing.Tracer]] that sends traces to scribe with the specified
   * scribeCategory.
   *
   * @param client The scribe client used to send traces to scribe
   * @param scribeCategory Category under which the trace data should be scribed
   * @param statsReceiver where to record Scribe stats
   * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
   */
  def apply(
    client: Scribe.MethodPerEndpoint,
    scribeCategory: String,
    statsReceiver: StatsReceiver,
    timer: Timer
  ): ScribeRawZipkinTracer = {
    val scribeStats = new ScribeStats(statsReceiver)
    new ScribeRawZipkinTracer(client, scribeStats, scribeCategory, timer)
  }

}

/**
 * Receives traces and sends them off to scribe with the specified scribeCategory.
 *
 * @param client The scribe client used to send traces to scribe
 * @param statsReceiver We generate stats to keep track of traces sent, failures and so on
 * @param scribeCategory scribe category under which the trace will be logged
 * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
 * @param poolSize The number of Memory transports to make available for serializing Spans
 * @param initialBufferSize Initial size of each transport
 * @param maxBufferSize Max size to keep around. Transports will grow as needed, but will revert back to `initialBufferSize` when reset if
 * they grow beyond `maxBufferSize`
 */
private[thrift] class ScribeRawZipkinTracer(
  client: Scribe.MethodPerEndpoint,
  scribeStats: ScribeStats,
  scribeCategory: String = "zipkin",
  timer: Timer = DefaultTimer,
  poolSize: Int = 10,
  initialBufferSize: StorageUnit = 512.bytes,
  maxBufferSize: StorageUnit = 1.megabyte)
    extends RawZipkinTracer(timer) {

  private[this] val initialSizeInBytes = initialBufferSize.inBytes.toInt
  private[this] val maxSizeInBytes = maxBufferSize.inBytes.toInt

  private class LimitedSizeByteArrayOutputStream(initSize: Int, maxSize: Int)
      extends TByteArrayOutputStream(initSize) {

    override def reset(): Unit = synchronized {
      if (buf.length > maxSize) {
        buf = new Array[Byte](maxSize)
      }
      super.reset()
    }
  }

  /**
   * A wrapper around the TReusableMemoryTransport from Scrooge that
   * also resets the size of the underlying buffer if it grows larger
   * than `maxBufferSize`
   */
  private class ReusableTransport {
    private[this] val thriftOutput =
      new LimitedSizeByteArrayOutputStream(initialSizeInBytes, maxSizeInBytes)

    private[this] val transport = new TReusableMemoryTransport(thriftOutput)
    val protocol: TProtocol = Protocols.binaryFactory().getProtocol(transport)

    private[this] val base64Output =
      new LimitedSizeByteArrayOutputStream(initialSizeInBytes, maxSizeInBytes)

    def reset(): Unit = {
      transport.reset()
      base64Output.reset()
    }

    def toBase64Line: String = {
      // encode into a reusable OutputStream
      val out = Base64.getEncoder.wrap(base64Output)
      out.write(thriftOutput.get(), 0, thriftOutput.len())
      out.flush()
      out.close()

      // ensure there is space in the byte array for a trailing \n
      val encBytes = base64Output.get()
      val encLen = base64Output.len()
      val withNewline: Array[Byte] =
        if (encLen + 1 <= encBytes.length) encBytes
        else Arrays.copyOf(encBytes, encBytes.length + 1)

      // add the trailing '\n'
      withNewline(encLen) = '\n'
      new String(withNewline, 0, encLen + 1, StandardCharsets.US_ASCII)
    }
  }

  private[this] val bufferPool = new ArrayBlockingQueue[ReusableTransport](poolSize)
  0.until(poolSize).foreach { _ => bufferPool.add(new ReusableTransport) }

  /**
   * Serialize the span, base64 encode and shove it all in a list.
   */
  private[this] def createLogEntries(spans: Seq[Span]): Seq[LogEntry] = {
    val entries = new ArrayBuffer[LogEntry](spans.size)

    spans.foreach { span =>
      val transport = bufferPool.take()
      try {
        span.toThrift.write(transport.protocol)
        entries.append(LogEntry(category = scribeCategory, message = transport.toBase64Line))
      } catch {
        case NonFatal(e) => scribeStats.handleError(e)
      } finally {
        transport.reset()
        bufferPool.add(transport)
      }
    }

    entries.toSeq
  }

  /**
   * Log the span data via Scribe.
   */
  def sendSpans(spans: Seq[Span]): Future[Unit] = {
    val logEntries = createLogEntries(spans)
    if (logEntries.isEmpty) Future.Done
    else client.log(logEntries).unit
  }
}
