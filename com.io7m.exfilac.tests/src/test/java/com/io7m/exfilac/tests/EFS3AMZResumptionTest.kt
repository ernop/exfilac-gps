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

import com.io7m.exfilac.s3_uploader.amazon.EFS3AMZChunk
import com.io7m.exfilac.s3_uploader.amazon.EFS3AMZResumption
import com.io7m.exfilac.s3_uploader.amazon.EFS3AMZResumption.ExistingPart
import com.io7m.exfilac.s3_uploader.amazon.EFS3AMZResumption.ExistingUpload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EFS3AMZResumptionTest {

  private val chunks =
    listOf(
      EFS3AMZChunk(partNumber = 1, chunkSize = 10L, chunkOffset = 0L),
      EFS3AMZChunk(partNumber = 2, chunkSize = 10L, chunkOffset = 10L),
      EFS3AMZChunk(partNumber = 3, chunkSize = 5L, chunkOffset = 20L)
    )

  private val md5s =
    mapOf(Pair(1, "aa"), Pair(2, "bb"), Pair(3, "cc"))

  @Test
  fun testNoUploads() {
    assertNull(EFS3AMZResumption.choose(this.chunks, this.md5s, listOf()))
  }

  @Test
  fun testMatchingUploadIsChosen() {
    val r =
      EFS3AMZResumption.choose(
        this.chunks,
        this.md5s,
        listOf(ExistingUpload("u", listOf(ExistingPart(1, 10L, "\"AA\""), ExistingPart(2, 10L, "\"bb\""))))
      )

    assertEquals("u", r?.uploadId)
    assertEquals(mapOf(Pair(1, "\"AA\""), Pair(2, "\"bb\"")), r?.parts)
  }

  @Test
  fun testPartWithOtherContentRejectsUpload() {
    val r =
      EFS3AMZResumption.choose(
        this.chunks,
        this.md5s,
        listOf(ExistingUpload("u", listOf(ExistingPart(1, 10L, "\"aa\""), ExistingPart(2, 10L, "\"ff\""))))
      )

    assertNull(r)
  }

  @Test
  fun testPartWithOtherSizeRejectsUpload() {
    val r =
      EFS3AMZResumption.choose(
        this.chunks,
        this.md5s,
        listOf(ExistingUpload("u", listOf(ExistingPart(1, 9L, "\"aa\""))))
      )

    assertNull(r)
  }

  @Test
  fun testPartBeyondFileRejectsUpload() {
    val r =
      EFS3AMZResumption.choose(
        this.chunks,
        this.md5s,
        listOf(ExistingUpload("u", listOf(ExistingPart(1, 10L, "\"aa\""), ExistingPart(4, 10L, "\"aa\""))))
      )

    assertNull(r)
  }

  @Test
  fun testUploadWithoutPartsIsNotChosen() {
    assertNull(EFS3AMZResumption.choose(this.chunks, this.md5s, listOf(ExistingUpload("u", listOf()))))
  }

  @Test
  fun testUploadHoldingMostOctetsIsChosen() {
    val r =
      EFS3AMZResumption.choose(
        this.chunks,
        this.md5s,
        listOf(
          ExistingUpload("one", listOf(ExistingPart(1, 10L, "\"aa\""))),
          ExistingUpload("two", listOf(ExistingPart(1, 10L, "\"aa\""), ExistingPart(3, 5L, "\"cc\""))),
          ExistingUpload("other", listOf(ExistingPart(1, 10L, "\"aa\""), ExistingPart(2, 10L, "\"00\""))),
        )
      )

    assertEquals("two", r?.uploadId)
  }
}
