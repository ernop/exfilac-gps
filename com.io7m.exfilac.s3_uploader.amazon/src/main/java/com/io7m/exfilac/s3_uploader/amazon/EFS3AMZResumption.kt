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

package com.io7m.exfilac.s3_uploader.amazon

import java.util.Locale

/**
 * Decides which unfinished multi-part upload of a file can be continued.
 */

object EFS3AMZResumption {

  data class ExistingPart(
    val partNumber: Int,
    val size: Long,
    val eTag: String
  )

  data class ExistingUpload(
    val uploadId: String,
    val parts: List<ExistingPart>
  )

  data class Resumption(
    val uploadId: String,
    val parts: Map<Int, String>
  )

  /**
   * An unfinished upload can be continued when it holds at least one part and every part it
   * holds has the size and the MD5 of the same part of the local file. Of those, the one holding
   * the most octets is chosen. The parts of the result map part numbers to their ETags.
   */

  fun choose(
    chunks: List<EFS3AMZChunk>,
    localMD5s: Map<Int, String>,
    uploads: List<ExistingUpload>
  ): Resumption? {
    val chunksByNumber = chunks.associateBy { c -> c.partNumber }
    var best: Resumption? = null
    var bestOctets = 0L

    for (upload in uploads) {
      val parts = mutableMapOf<Int, String>()
      var octets = 0L
      for (part in upload.parts) {
        val chunk = chunksByNumber[part.partNumber]
        val md5 = localMD5s[part.partNumber]
        if (chunk == null || md5 == null || chunk.chunkSize != part.size || md5 != md5OfETag(part.eTag)) {
          parts.clear()
          break
        }
        parts[part.partNumber] = part.eTag
        octets += part.size
      }
      if (parts.isNotEmpty() && octets > bestOctets) {
        best = Resumption(upload.uploadId, parts.toMap())
        bestOctets = octets
      }
    }
    return best
  }

  /**
   * S3 returns the ETag of a part as the part's MD5 in lowercase hexadecimal, in double quotes.
   */

  fun md5OfETag(
    eTag: String
  ): String {
    return eTag.trim().removeSurrounding("\"").lowercase(Locale.ROOT)
  }
}
