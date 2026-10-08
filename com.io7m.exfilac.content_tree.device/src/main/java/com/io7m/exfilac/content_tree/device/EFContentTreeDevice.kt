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

package com.io7m.exfilac.content_tree.device

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.io7m.exfilac.content_tree.api.EFContentDirectoryType
import com.io7m.exfilac.content_tree.api.EFContentFileType
import com.io7m.exfilac.content_tree.api.EFContentPath
import com.io7m.exfilac.content_tree.api.EFContentTreeFactoryType
import com.io7m.exfilac.content_tree.api.EFContentTreeNodeType
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC

class EFContentTreeDevice(
  private val context: Application,
  private val contentResolver: ContentResolver
) : EFContentTreeFactoryType {

  private sealed class Node(
    override val path: EFContentPath
  ) : EFContentTreeNodeType

  private inner class FileNode(
    path: EFContentPath,
    override val contentURI: URI,
    override val lastModified: OffsetDateTime,
    override val size: Long,
    private var parentReference: DirectoryNode?
  ) : Node(path), EFContentFileType {
    override val parent: EFContentDirectoryType?
      get() = this.parentReference

    override fun read(): InputStream {
      return contentResolver.openInputStream(
        originalUriOf(Uri.parse(contentURI.toString()))
      ) ?: throw IOException("Failed to open input stream: $contentURI")
    }

    override fun toString(): String {
      return "[File $contentURI]"
    }
  }

  private class DirectoryNode(
    path: EFContentPath,
    private var parentReference: DirectoryNode?,
    override val lastModified: OffsetDateTime,
    val childrenList: MutableList<EFContentTreeNodeType>
  ) : Node(path), EFContentDirectoryType {
    override val parent: EFContentDirectoryType?
      get() = this.parentReference
    override val children: List<EFContentTreeNodeType>
      get() = this.childrenList.toList()

    override fun toString(): String {
      return "[Directory ${path.path}]"
    }
  }

  /*
   * A read through a document URI is opened by the document provider, so MediaProvider
   * zeroes the location fields of images (Exif) and MP4-family media files. The MediaStore
   * URI is opened as this application: MediaProvider returns the file as written while
   * ACCESS_MEDIA_LOCATION is granted, and otherwise the zeroed copy without an error, so the
   * read refuses to start without it. MediaStore.setRequireOriginal cannot make that check:
   * getMediaUri grants access to the URI without a query, MediaProvider matches the grant
   * against the URI including "?requireOriginal=1", and every open fails with "has no
   * access". MediaStore has no entry for trashed, pending and .thumbnails files; treeWalk skips
   * them as hidden entries before they get here.
   */

  private fun originalUriOf(
    documentUri: Uri
  ): Uri {
    val mimeType = this.contentResolver.getType(documentUri)
      ?: throw IOException("No MIME type for $documentUri")
    if (listOf("image/", "video/", "audio/").none { prefix -> mimeType.startsWith(prefix) }) {
      return documentUri
    }
    val permission = this.context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (permission != PackageManager.PERMISSION_GRANTED) {
      throw IOException(
        "ACCESS_MEDIA_LOCATION is not granted; $documentUri would be read with its location removed"
      )
    }
    return try {
      MediaStore.getMediaUri(this.context, documentUri)
    } catch (e: IllegalArgumentException) {
      throw IOException("MediaStore has no entry for $documentUri", e)
    } ?: throw IOException("MediaStore returned no URI for $documentUri")
  }

  override fun create(
    source: URI
  ): EFContentTreeNodeType {
    return this.createActual(context as Context, source)
  }

  private fun createActual(
    context: Context,
    source: URI
  ): EFContentTreeNodeType {
    val sourceURI =
      Uri.parse(source.toString())

    /*
     * We, again, try to obtain persistent permissions to read from the given source. The caller
     * should already have done this, but Android is a mess of security theater and incompetence,
     * and there don't appear to be any downsides to requesting the permission again.
     */

    this.contentResolver.takePersistableUriPermission(
      sourceURI,
      Intent.FLAG_GRANT_READ_URI_PERMISSION
    )

    val root = DocumentFile.fromTreeUri(context, sourceURI)
      ?: throw IOException("Could not open $source as a filesystem.")

    return this.treeWalk(
      source = source,
      path = listOf(),
      parent = null,
      element = root,
    )
  }

  private fun treeWalk(
    source: URI,
    path: List<String>,
    parent: DirectoryNode?,
    element: DocumentFile
  ): EFContentTreeNodeType {
    if (element.isFile) {
      return FileNode(
        path = EFContentPath(source, path.plus(element.name!!)),
        contentURI = URI.create(element.uri.toString()),
        size = element.length(),
        lastModified = lastModifiedOf(element),
        parentReference = parent
      )
    }

    if (element.isVirtual) {
      return FileNode(
        path = EFContentPath(source, path.plus(element.name!!)),
        contentURI = URI.create(element.uri.toString()),
        size = element.length(),
        lastModified = lastModifiedOf(element),
        parentReference = parent
      )
    }

    if (element.isDirectory) {
      val newParent = DirectoryNode(
        path = EFContentPath(source, path.plus(element.name!!)),
        parentReference = parent,
        lastModified = lastModifiedOf(element),
        childrenList = mutableListOf()
      )
      for (subNode in element.listFiles()) {
        if (isHidden(subNode)) {
          continue
        }
        newParent.childrenList.add(
          this.treeWalk(
            source = source,
            path = newParent.path.path,
            parent = newParent,
            element = subNode
          )
        )
      }
      return newParent
    }

    throw IllegalStateException("File is not a file, directory, or virtual.")
  }

  /*
   * Entries whose names start with "." are hidden: .thumbnails, .trashed-*, .pending-*, .nomedia
   * and the like. MediaStore has no entry for most of them, so reading them would fail on every
   * run, and a hidden directory such as .thumbnails can hold thousands of files. They are skipped
   * without listing their contents.
   */

  private fun isHidden(
    element: DocumentFile
  ): Boolean {
    return element.name?.startsWith(".") == true
  }

  private fun lastModifiedOf(
    element: DocumentFile
  ): OffsetDateTime {
    return OffsetDateTime.ofInstant(Instant.ofEpochMilli(element.lastModified()), UTC)
  }
}
