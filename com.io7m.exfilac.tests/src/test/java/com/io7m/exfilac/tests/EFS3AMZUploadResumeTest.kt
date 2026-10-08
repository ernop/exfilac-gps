/*
 * Copyright © 2026 Exfilac GPS contributors
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

package com.io7m.exfilac.tests

import com.io7m.exfilac.s3_uploader.amazon.EFS3AMZUpload
import com.io7m.exfilac.s3_uploader.api.EFS3UploadRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import com.io7m.peixoto.sdk.software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import com.io7m.peixoto.sdk.software.amazon.awssdk.core.sync.RequestBody
import com.io7m.peixoto.sdk.software.amazon.awssdk.http.apache.ApacheHttpClient
import com.io7m.peixoto.sdk.software.amazon.awssdk.regions.Region
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.S3Client
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CreateBucketRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.GetObjectRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.HeadObjectRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.ListPartsRequest
import com.io7m.peixoto.sdk.software.amazon.awssdk.services.s3.model.UploadPartRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.UUID

/**
 * Uploads to a real S3 endpoint named by the environment variable EXFILAC_TEST_S3_ENDPOINT, for
 * example a local moto server (`uvx --from 'moto[server]' moto_server -p 5055` and
 * `http://127.0.0.1:5055`). The tests are skipped when it is not set.
 */

class EFS3AMZUploadResumeTest {

  private val chunkSize = 8_388_608
  private val fileSize = 37 * 1_048_576

  private lateinit var endpoint: URI
  private lateinit var client: S3Client
  private lateinit var bucket: String
  private val events = mutableListOf<String>()

  /**
   * The content of a file. Every stream after the first, which is the content hash, fails when a
   * read would go past failAt octets, as long as failures remain.
   */

  private class Source(
    val data: ByteArray,
    val failAt: Long,
    var failures: Int
  ) {
    var opens = 0

    fun open(): InputStream {
      this.opens += 1
      val stream = this.data.inputStream()
      if (this.opens == 1) {
        return stream
      }

      return object : FilterInputStream(stream) {
        private var position = 0L

        override fun read(): Int {
          this.check(1)
          val r = super.read()
          if (r != -1) {
            this.position += 1
          }
          return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          this.check(len)
          val r = super.read(b, off, len)
          if (r > 0) {
            this.position += r
          }
          return r
        }

        override fun skip(n: Long): Long {
          val s = super.skip(n)
          this.position += s
          return s
        }

        private fun check(len: Int) {
          if (failures > 0 && this.position + len > failAt) {
            failures -= 1
            throw IOException("Simulated failure at octet ${this.position}")
          }
        }
      }
    }
  }

  @BeforeEach
  fun setup() {
    val endpointText = System.getenv("EXFILAC_TEST_S3_ENDPOINT")
    Assumptions.assumeTrue(endpointText != null, "EXFILAC_TEST_S3_ENDPOINT is not set")

    this.endpoint = URI.create(endpointText!!)
    this.client =
      S3Client.builder()
        .endpointOverride(this.endpoint)
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "testtesttest"))
        )
        .httpClient(ApacheHttpClient.builder().build())
        .build()

    this.bucket = "exfilac-test-${UUID.randomUUID()}"
    this.client.createBucket(CreateBucketRequest.builder().bucket(this.bucket).build())
  }

  @AfterEach
  fun tearDown() {
    if (this::client.isInitialized) {
      this.client.close()
    }
  }

  @Test
  fun testInterruptedUploadContinuesOnNextRun() {
    val data = randomBytes(this.fileSize, 1L)
    val key = "DCIM/Camera/interrupted.mp4"

    val failing = Source(data, failAt = 2L * this.chunkSize, failures = Int.MAX_VALUE)
    assertThrows(Exception::class.java) {
      this.upload(key, failing)
    }
    assertTrue(
      this.events.contains("The upload stopped with 2 of 5 parts uploaded; the next run continues from there."),
      this.events.toString()
    )
    val unfinished = this.unfinishedUploads(key)
    assertEquals(1, unfinished.size)
    assertEquals(listOf(1, 2), this.partNumbers(key, unfinished[0]))

    this.events.clear()
    this.upload(key, Source(data, failAt = Long.MAX_VALUE, failures = 0))

    assertTrue(
      this.events.contains("Continuing an earlier multi-part upload: 2 of 5 parts are already uploaded."),
      this.events.toString()
    )
    assertEquals(3, this.events.count { e -> e.startsWith("Uploading part ") })
    assertArrayEquals(data, this.download(key))
    assertEquals(sha256Of(data), this.metadataHash(key))
    assertEquals(listOf<String>(), this.unfinishedUploads(key))
  }

  @Test
  fun testFailedPartIsRetriedInTheSameRun() {
    val data = randomBytes(this.fileSize, 2L)
    val key = "DCIM/Camera/retried.mp4"
    val source = Source(data, failAt = 12L * 1_048_576, failures = 1)

    this.upload(key, source)

    assertEquals(0, source.failures)
    assertArrayEquals(data, this.download(key))
    assertEquals(listOf<String>(), this.unfinishedUploads(key))
  }

  @Test
  fun testUploadOfOtherContentIsCancelledAfterSuccess() {
    val data = randomBytes(this.fileSize, 3L)
    val key = "DCIM/Camera/changed.mp4"
    val stale =
      this.client.createMultipartUpload(
        CreateMultipartUploadRequest.builder().bucket(this.bucket).key(key).build()
      ).uploadId()
    this.client.uploadPart(
      UploadPartRequest.builder()
        .bucket(this.bucket)
        .key(key)
        .uploadId(stale)
        .partNumber(1)
        .contentLength(this.chunkSize.toLong())
        .build(),
      RequestBody.fromBytes(randomBytes(this.chunkSize, 4L))
    )

    this.upload(key, Source(data, failAt = Long.MAX_VALUE, failures = 0))

    assertTrue(this.events.contains("Uploading as 5 chunks."), this.events.toString())
    assertTrue(this.events.contains("Cancelled unfinished multi-part upload $stale."), this.events.toString())
    assertArrayEquals(data, this.download(key))
    assertEquals(listOf<String>(), this.unfinishedUploads(key))
  }

  @Test
  fun testSmallFileIsUploadedThenSkipped() {
    val data = randomBytes(1_048_576, 5L)
    val key = "Pictures/small.jpg"

    this.upload(key, Source(data, failAt = Long.MAX_VALUE, failures = 0))
    assertArrayEquals(data, this.download(key))
    assertEquals(sha256Of(data), this.metadataHash(key))

    this.events.clear()
    this.upload(key, Source(data, failAt = Long.MAX_VALUE, failures = 0))
    assertTrue(this.events.contains("Hashes and size match, no upload is required."), this.events.toString())
  }

  private fun upload(
    key: String,
    source: Source
  ) {
    val request =
      EFS3UploadRequest(
        accessKey = "test",
        secretKey = "testtesttest",
        region = "us-east-1",
        endpoint = this.endpoint,
        bucket = this.bucket,
        path = key,
        contentType = "application/octet-stream",
        size = source.data.size.toLong(),
        streams = source::open,
        pathStyle = true,
        temporaryDirectory = Paths.get("/tmp"),
        onStatistics = { },
        onInformativeEvent = { message -> this.events.add(message) },
        onError = { },
        onFileSkipped = { },
        onFileSuccessfullyUploaded = { }
      )
    EFS3AMZUpload(request, EFClockMock).use { u -> u.execute() }
  }

  private fun unfinishedUploads(
    key: String
  ): List<String> {
    return this.client.listMultipartUploads(
      ListMultipartUploadsRequest.builder().bucket(this.bucket).prefix(key).build()
    ).uploads()
      .filter { u -> u.key() == key }
      .map { u -> u.uploadId() }
  }

  private fun partNumbers(
    key: String,
    uploadId: String
  ): List<Int> {
    return this.client.listParts(
      ListPartsRequest.builder().bucket(this.bucket).key(key).uploadId(uploadId).build()
    ).parts().map { p -> p.partNumber() }
  }

  private fun download(
    key: String
  ): ByteArray {
    return this.client.getObjectAsBytes(
      GetObjectRequest.builder().bucket(this.bucket).key(key).build()
    ).asByteArray()
  }

  private fun metadataHash(
    key: String
  ): String? {
    return this.client.headObject(
      HeadObjectRequest.builder().bucket(this.bucket).key(key).build()
    ).metadata().entries.firstOrNull { e -> e.key.equals("exfilac-sha256", ignoreCase = true) }?.value
  }

  companion object {
    private fun randomBytes(
      size: Int,
      seed: Long
    ): ByteArray {
      val bytes = ByteArray(size)
      Random(seed).nextBytes(bytes)
      return bytes
    }

    private fun sha256Of(
      data: ByteArray
    ): String {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data))
    }
  }
}
