/*
 * Copyright © 2024 Mark Raynsford <code@io7m.com> https://www.io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.io7m.exfilac.s3_uploader.amazon

import com.io7m.exfilac.clock.api.EFClockServiceType
import com.io7m.exfilac.s3_uploader.api.EFS3TransferStatistics
import com.io7m.exfilac.s3_uploader.api.EFS3UploadRequest
import com.io7m.exfilac.s3_uploader.api.EFS3UploadType
import com.io7m.jmulticlose.core.CloseableCollection
import com.io7m.peixoto.sdk.org.apache.commons.codec.binary.Base64
import com.io7m.peixoto.sdk.software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import com.io7m.peixoto.sdk.software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import com.io7m.peixoto.sdk.software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import com.io7m.peixoto.sdk.software.amazon.awssdk.core.sync.RequestBody
import com.io7m.peixoto.sdk.software.amazon.awssdk.http.ContentStreamProvider
import com.io7m.peixoto.sdk.software.amazon.awssdk.http.SdkHttpClient
import com.io7m.peixoto.sdk.software.amazon.awssdk.http.apache.ApacheHttpClient
import com.io7m.peixoto.sdk.software.amazon.awssdk.regions.Region
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.S3Client
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CompletedPart
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.HeadObjectRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.HeadObjectResponse
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.ListPartsRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.NoSuchKeyException
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.PutObjectRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.S3Exception
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.UploadPartRequest
import org.apache.commons.io.input.BoundedInputStream
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EFS3AMZUpload(
  private val upload: EFS3UploadRequest,
  private val clock: EFClockServiceType,
) : EFS3UploadType {

  private val logger =
    LoggerFactory.getLogger(EFS3AMZUpload::class.java)

  private val exfilacSHA256Header =
    "EXFILAC-SHA256"
  private val multipartThreshold =
    16_777_216L
  private val minimumChunkSize =
    8_388_608L
  private val maximumChunkCount =
    900L

  private val resources =
    CloseableCollection.create()

  private lateinit var executor: ExecutorService
  private lateinit var httpClient: SdkHttpClient
  private lateinit var s3client: S3Client

  private val done =
    AtomicBoolean()
  private val streamSupervised: AtomicReference<BoundedInputStream> =
    AtomicReference()
  private val streamsOpened =
    mutableListOf<InputStream>()

  @Volatile
  private var octetsThen = 0L

  /*
   * The octets of the parts of a multi-part upload that are already in the bucket. The stream
   * supervisor adds the octets read so far from the stream of the part being sent.
   */

  @Volatile
  private var octetsCompleted = 0L

  override fun execute() {
    this.executor =
      Executors.newSingleThreadExecutor { r ->
        val thread = Thread(r)
        thread.name = "com.io7m.exfilac.s3.upload_supervisor[${thread.id}]"
        thread.priority = Thread.MIN_PRIORITY
        thread
      }

    this.resources.add(AutoCloseable { this.executor.shutdown() })

    try {
      this.executor.execute(this::executeStreamSupervisor)

      val credentials =
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(this.upload.accessKey, this.upload.secretKey)
        )

      this.httpClient =
        this.resources.add(
          ApacheHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(60L))
            .socketTimeout(Duration.ofSeconds(60L))
            .build()
        )

      val clientBuilder = S3Client.builder()
      clientBuilder.credentialsProvider(credentials)
      clientBuilder.httpClient(this.httpClient)
      clientBuilder.region(Region.of(this.upload.region))
      clientBuilder.region(Region.of(this.upload.region))
      clientBuilder.forcePathStyle(this.upload.pathStyle)
      clientBuilder.endpointOverride(this.upload.endpoint)

      val strategy =
        AwsRetryStrategy.standardRetryStrategy()
          .toBuilder()
          .maxAttempts(5)
          .build()

      clientBuilder.overrideConfiguration { o -> o.retryStrategy(strategy) }

      return clientBuilder.build().use { c ->
        this.s3client = this.resources.add(c)
        if (this.upload.size >= this.multipartThreshold) {
          this.executeUploadMultiPart(c)
        } else {
          this.executeUploadSimple(c)
        }
      }
    } catch (e: Throwable) {
      this.upload.onError(e)
      throw e
    } finally {
      this.done.set(true)
      this.executor.shutdown()
      this.executor.awaitTermination(5L, TimeUnit.SECONDS)
    }
  }

  private fun executeUploadSimple(
    client: S3Client
  ) {
    this.upload.onInformativeEvent("Calculating local content hash.")
    val contentSHA256 = this.localDigests(listOf()).sha256
    this.upload.onInformativeEvent("Local content hash: $contentSHA256")

    if (!this.isUploadNecessary(client, contentSHA256, recordSkipped = true)) {
      return
    }

    val metadata =
      mapOf(Pair(this.exfilacSHA256Header, contentSHA256))

    val put =
      PutObjectRequest.builder()
        .bucket(this.upload.bucket)
        .contentLength(this.upload.size)
        .contentType(this.upload.contentType)
        .metadata(metadata)
        .key(this.upload.path)
        .build()

    this.upload.onInformativeEvent("Uploading file.")
    try {
      client.putObject(put, this.requestBodyOf(0L, this.upload.size))
    } finally {
      this.closeStreams()
    }

    if (this.isUploadNecessary(client, contentSHA256, recordSkipped = false)) {
      throw IOException("After uploading, the size or hash does not match!")
    }

    this.upload.onInformativeEvent("Uploading completed.")
    this.upload.onFileSuccessfullyUploaded()
  }

  /*
   * The stream supervisor function. This runs on a dedicated thread and periodically examines
   * the current stream to see how much data is being transferred.
   */

  private fun executeStreamSupervisor() {
    while (!this.done.get()) {
      val stream = this.streamSupervised.get()
      if (stream != null) {
        try {
          val octetsTransferredNow = this.octetsCompleted + stream.count
          val octetsInPeriod = octetsTransferredNow - this.octetsThen
          this.octetsThen = octetsTransferredNow
          this.upload.onStatistics.invoke(
            EFS3TransferStatistics(
              time = this.clock.now(),
              octetsTransferred = octetsTransferredNow,
              octetsExpected = this.upload.size,
              octetsThisPeriod = octetsInPeriod
            )
          )
        } catch (e: Throwable) {
          this.logger.debug("Uncaught statistics subscriber exception: ", e)
        }
      }

      try {
        Thread.sleep(100L)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  private fun isUploadNecessary(
    client: S3Client,
    contentSHA256: String,
    recordSkipped: Boolean
  ): Boolean {
    try {
      this.upload.onInformativeEvent("Fetching remote content hash.")
      val head =
        HeadObjectRequest.builder()
          .bucket(this.upload.bucket)
          .key(this.upload.path)
          .build()

      val response = client.headObject(head)
      val remoteSize = response.contentLength()
      val remoteHash = fixMetadataCase(response)[this.exfilacSHA256Header]
      this.upload.onInformativeEvent("Remote content hash: $remoteHash")
      if (remoteHash == contentSHA256 && this.upload.size == remoteSize) {
        this.upload.onInformativeEvent("Hashes and size match, no upload is required.")
        if (recordSkipped) {
          this.upload.onFileSkipped()
        }
        return false
      }
    } catch (e: NoSuchKeyException) {
      this.upload.onInformativeEvent("Remote file does not exist. Upload is required.")
    }
    return true
  }

  private fun fixMetadataCase(
    response: HeadObjectResponse
  ): Map<String, String> {
    val existing = response.metadata()
    val result = mutableMapOf<String, String>()
    for (entry in existing) {
      result[entry.key.uppercase(Locale.ROOT)] = entry.value
    }
    return result.toMap()
  }

  private class LocalDigests(
    val sha256: String,
    val partMD5s: Map<Int, String>
  )

  /*
   * Read the file once for its SHA-256 and, given the parts of a multi-part upload, the MD5 of
   * each part. The file must still have the size it was listed with; a file that changed since
   * then fails here and is uploaded on a later run.
   */

  private fun localDigests(
    chunks: List<EFS3AMZChunk>
  ): LocalDigests {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val partMD5s = mutableMapOf<Int, String>()
    val ranges = chunks.ifEmpty { listOf(EFS3AMZChunk(1, this.upload.size, 0L)) }
    val buffer = ByteArray(65536)

    this.upload.streams.invoke().use { stream ->
      for (range in ranges) {
        val md5 = if (chunks.isEmpty()) null else MessageDigest.getInstance("MD5")
        var remaining = range.chunkSize
        while (remaining > 0L) {
          val r = stream.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
          if (r == -1) {
            throw IOException("The file is shorter than the ${this.upload.size} octets it was listed with.")
          }
          sha256.update(buffer, 0, r)
          md5?.update(buffer, 0, r)
          remaining -= r
        }
        if (md5 != null) {
          partMD5s[range.partNumber] = hexOf(md5.digest())
        }
      }
      if (stream.read() != -1) {
        throw IOException("The file is longer than the ${this.upload.size} octets it was listed with.")
      }
    }
    return LocalDigests(Base64.encodeBase64String(sha256.digest()), partMD5s.toMap())
  }

  private fun hexOf(
    bytes: ByteArray
  ): String {
    val digits = "0123456789abcdef"
    val text = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      val v = b.toInt() and 0xff
      text.append(digits[v ushr 4])
      text.append(digits[v and 0x0f])
    }
    return text.toString()
  }

  /*
   * The SDK asks the content provider for a new stream for each attempt at a request, so a
   * request that fails part of the way through is retried from its first octet. A single stream
   * that cannot be rewound would leave every retry short of data.
   */

  private fun requestBodyOf(
    offset: Long,
    size: Long
  ): RequestBody {
    return RequestBody.fromContentProvider(
      ContentStreamProvider { this.openRange(offset, size) },
      size,
      this.upload.contentType
    )
  }

  private fun openRange(
    offset: Long,
    size: Long
  ): InputStream {
    val stream = this.upload.streams.invoke()
    try {
      this.skipExactly(stream, offset)
      val bounded =
        BoundedInputStream.builder()
          .setInputStream(stream)
          .setMaxCount(size)
          .get()
      synchronized(this.streamsOpened) {
        this.streamsOpened.add(bounded)
      }
      this.streamSupervised.set(bounded)
      return bounded
    } catch (e: Throwable) {
      stream.close()
      throw e
    }
  }

  /*
   * InputStream.skip on a file seeks; reading through the skipped octets would read the file up
   * to each part again.
   */

  private fun skipExactly(
    stream: InputStream,
    count: Long
  ) {
    var remaining = count
    while (remaining > 0L) {
      val skipped = stream.skip(remaining)
      if (skipped > 0L) {
        remaining -= skipped
      } else if (stream.read() == -1) {
        throw IOException("The file ended before octet $count.")
      } else {
        remaining -= 1L
      }
    }
  }

  private fun closeStreams() {
    this.streamSupervised.set(null)
    val streams =
      synchronized(this.streamsOpened) {
        val copy = this.streamsOpened.toList()
        this.streamsOpened.clear()
        copy
      }
    for (stream in streams) {
      try {
        stream.close()
      } catch (e: IOException) {
        this.logger.debug("Failed to close stream: ", e)
      }
    }
  }

  /*
   * A multi-part upload that fails is left in the bucket, and the next run continues it from
   * the parts that are already there. A continued upload keeps the metadata it was created
   * with, so if the file changed after its uploaded parts, the check after uploading fails and
   * the next run uploads the file again from the start.
   */

  private fun executeUploadMultiPart(
    client: S3Client
  ) {
    val chunks =
      EFS3AMZChunkSizeCalculation.calculate(
        size = this.upload.size,
        minimumChunkSize = this.minimumChunkSize,
        maximumChunkCount = this.maximumChunkCount
      )

    this.upload.onInformativeEvent("Calculating local content hash.")
    val local = this.localDigests(chunks)
    this.upload.onInformativeEvent("Local content hash: ${local.sha256}")

    if (!this.isUploadNecessary(client, local.sha256, recordSkipped = true)) {
      return
    }

    val unfinished = this.unfinishedUploads(client)
    val resumption = EFS3AMZResumption.choose(chunks, local.partMD5s, unfinished)
    val completedParts = mutableMapOf<Int, CompletedPart>()
    val uploadId: String

    if (resumption != null) {
      uploadId = resumption.uploadId
      for ((partNumber, eTag) in resumption.parts) {
        completedParts[partNumber] =
          CompletedPart.builder()
            .partNumber(partNumber)
            .eTag(eTag)
            .build()
      }
      this.upload.onInformativeEvent(
        "Continuing an earlier multi-part upload: ${completedParts.size} of ${chunks.size} parts are already uploaded."
      )
    } else {
      this.upload.onInformativeEvent("Uploading as ${chunks.size} chunks.")

      val metadata =
        mapOf(Pair(this.exfilacSHA256Header, local.sha256))

      val create =
        CreateMultipartUploadRequest.builder()
          .bucket(this.upload.bucket)
          .contentType(this.upload.contentType)
          .key(this.upload.path)
          .metadata(metadata)
          .build()

      this.upload.onInformativeEvent("Requesting multi-part upload…")
      uploadId = client.createMultipartUpload(create).uploadId()
    }

    this.octetsCompleted =
      chunks.filter { c -> completedParts.containsKey(c.partNumber) }
        .sumOf { c -> c.chunkSize }

    try {
      for (chunk in chunks) {
        if (completedParts.containsKey(chunk.partNumber)) {
          continue
        }

        this.upload.onInformativeEvent(
          "Uploading part ${chunk.partNumber} of ${chunks.size} (Size ${chunk.chunkSize})…"
        )
        val uploadPartResponse =
          try {
            client.uploadPart(
              UploadPartRequest.builder()
                .bucket(this.upload.bucket)
                .contentLength(chunk.chunkSize)
                .key(this.upload.path)
                .partNumber(chunk.partNumber)
                .uploadId(uploadId)
                .build(),
              this.requestBodyOf(chunk.chunkOffset, chunk.chunkSize)
            )
          } finally {
            this.closeStreams()
          }

        completedParts[chunk.partNumber] =
          CompletedPart.builder()
            .partNumber(chunk.partNumber)
            .eTag(uploadPartResponse.eTag())
            .build()
        this.octetsCompleted += chunk.chunkSize
      }

      this.upload.onInformativeEvent("Completing multi-part upload…")
      val completedUpload =
        CompletedMultipartUpload.builder()
          .parts(completedParts.values.sortedBy { p -> p.partNumber() })
          .build()

      client.completeMultipartUpload(
        CompleteMultipartUploadRequest.builder()
          .multipartUpload(completedUpload)
          .uploadId(uploadId)
          .bucket(this.upload.bucket)
          .key(this.upload.path)
          .build()
      )
    } catch (e: Throwable) {
      this.onMultiPartFailed(client, uploadId, e, completedParts.size, chunks.size)
      throw e
    }

    if (this.isUploadNecessary(client, local.sha256, recordSkipped = false)) {
      throw IOException("After uploading, the size or hash does not match!")
    }

    /*
     * The other unfinished uploads of this file can never be completed now, and their parts
     * would stay in the bucket.
     */

    for (other in unfinished) {
      if (other.uploadId != uploadId) {
        this.abortQuietly(client, other.uploadId)
      }
    }

    this.upload.onInformativeEvent("Uploading completed.")
    this.upload.onFileSuccessfullyUploaded()
  }

  /*
   * The unfinished multi-part uploads of this file and their parts. A bucket or key that does
   * not allow listing them gives none, and the file is uploaded from the start.
   */

  private fun unfinishedUploads(
    client: S3Client
  ): List<EFS3AMZResumption.ExistingUpload> {
    val uploads = mutableListOf<EFS3AMZResumption.ExistingUpload>()
    try {
      val listed =
        client.listMultipartUploadsPaginator(
          ListMultipartUploadsRequest.builder()
            .bucket(this.upload.bucket)
            .prefix(this.upload.path)
            .build()
        ).uploads().filter { u -> u.key() == this.upload.path }

      for (listedUpload in listed) {
        val parts =
          client.listPartsPaginator(
            ListPartsRequest.builder()
              .bucket(this.upload.bucket)
              .key(this.upload.path)
              .uploadId(listedUpload.uploadId())
              .build()
          ).parts().map { p ->
            EFS3AMZResumption.ExistingPart(p.partNumber(), p.size(), p.eTag())
          }
        uploads.add(EFS3AMZResumption.ExistingUpload(listedUpload.uploadId(), parts))
      }
    } catch (e: Exception) {
      this.logger.debug("Failed to list unfinished uploads: ", e)
      this.upload.onInformativeEvent(
        "Could not list unfinished uploads of this file (${e.message}); uploading it from the start."
      )
      return listOf()
    }

    if (uploads.isNotEmpty()) {
      this.upload.onInformativeEvent("Unfinished uploads of this file in the bucket: ${uploads.size}.")
    }
    return uploads.toList()
  }

  private fun onMultiPartFailed(
    client: S3Client,
    uploadId: String,
    e: Throwable,
    partsUploaded: Int,
    partsTotal: Int
  ) {
    val errorCode = (e as? S3Exception)?.awsErrorDetails()?.errorCode()
    if (errorCode == "InvalidPart" || errorCode == "InvalidPartOrder" || errorCode == "EntityTooSmall") {
      this.abortQuietly(client, uploadId)
      return
    }
    this.upload.onInformativeEvent(
      "The upload stopped with $partsUploaded of $partsTotal parts uploaded; the next run continues from there."
    )
  }

  private fun abortQuietly(
    client: S3Client,
    uploadId: String
  ) {
    try {
      client.abortMultipartUpload(
        AbortMultipartUploadRequest.builder()
          .bucket(this.upload.bucket)
          .key(this.upload.path)
          .uploadId(uploadId)
          .build()
      )
      this.upload.onInformativeEvent("Cancelled unfinished multi-part upload $uploadId.")
    } catch (e: Exception) {
      this.logger.debug("Failed to cancel multi-part upload: ", e)
      this.upload.onInformativeEvent("Could not cancel unfinished multi-part upload $uploadId: ${e.message}")
    }
  }

  override fun close() {
    this.done.set(true)

    try {
      this.resources.close()
    } catch (e: Throwable) {
      this.logger.debug("Failed to close S3 client: ", e)
    }
  }
}
