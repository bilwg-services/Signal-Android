/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.Result
import org.signal.core.util.isAbsent
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.Debouncer
import org.thoughtcrime.securesms.util.rx.RxStore
import java.util.Optional

/**
 * Rewrite of [LinkPreviewViewModel] preferring Rx and Kotlin
 */
class LinkPreviewViewModelV2(
  private val linkPreviewRepository: LinkPreviewRepository = LinkPreviewRepository(),
  private val enablePlaceholder: Boolean
) : ViewModel() {
  private var enabled = SignalStore.settings().isLinkPreviewsEnabled
  private val linkPreviewStateStore = RxStore<LinkPreviewState>(LinkPreviewState.forNoLinks())

  val linkPreviewState: Flowable<LinkPreviewState> = linkPreviewStateStore.stateFlowable
  val hasLinkPreview: Boolean = linkPreviewStateStore.state.linkPreview.isPresent
  val hasLinkPreviewUi: Boolean = linkPreviewStateStore.state.hasContent()

  private var activeUrl: String? = null
  private var activeRequest: Disposable = Disposable.disposed()
  private var userCancelled: Boolean = false
  private val debouncer: Debouncer = Debouncer(250)

  override fun onCleared() {
    activeRequest.dispose()
    debouncer.clear()
  }

  fun onSend(): List<LinkPreview> {
    val currentState = linkPreviewStateStore.state

    onUserCancel()

    return currentState.linkPreview.map { listOf(it) }.orElse(emptyList())
  }

  fun onTextChanged(text: String, cursorStart: Int, cursorEnd: Int) {
    if (!enabled && !enablePlaceholder) {
      return
    }

    debouncer.publish {
      if (text.isEmpty()) {
        userCancelled = false
      }

      if (userCancelled) {
        return@publish
      }

      val link: Optional<Link> = LinkPreviewUtil.findValidPreviewUrls(text).findFirst()
      if (link.isPresent && link.get().url.equals(activeUrl)) {
        return@publish
      }

      activeRequest.dispose()

      if (link.isAbsent() || !isCursorPositionValid(text, link.get(), cursorStart, cursorEnd)) {
        activeUrl = null
        setLinkPreviewState(LinkPreviewState.forNoLinks())
        return@publish
      }

      setLinkPreviewState(LinkPreviewState.forLoading())

      val activeUrl = link.get().url
      this.activeUrl = activeUrl
      activeRequest = if (enabled) {
        performRequest(activeUrl)
      } else {
        createPlaceholder(activeUrl)
      }
    }
  }

  fun onEnabled() {
    userCancelled = false
    enabled = SignalStore.settings().isLinkPreviewsEnabled
  }

  fun onUserCancel() {
    activeRequest.dispose()
    userCancelled = true
    activeUrl = null
    debouncer.clear()
    setLinkPreviewState(LinkPreviewState.forNoLinks())
  }

  private fun isCursorPositionValid(text: String, link: Link, cursorStart: Int, cursorEnd: Int): Boolean {
    if (cursorStart != cursorEnd) {
      return true
    }

    if (text.endsWith(link.url) && cursorStart == link.position + link.url.length) {
      return true
    }

    return cursorStart < link.position || cursorStart > link.position + link.url.length
  }

  private fun createPlaceholder(url: String): Disposable {
    return Single.just(url)
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy {
        if (!userCancelled) {
          if (activeUrl != null && activeUrl == url) {
            setLinkPreviewState(LinkPreviewState.forLinksWithNoPreview(url, LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE))
          } else {
            setLinkPreviewState(LinkPreviewState.forNoLinks())
          }
        }
      }
  }

  private fun performRequest(url: String): Disposable {
    return linkPreviewRepository.getLinkPreview(url)
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy { result ->
        if (!userCancelled) {
          val linkPreviewState = when (result) {
            is Result.Success -> if (activeUrl == result.success.url) LinkPreviewState.forPreview(result.success) else LinkPreviewState.forNoLinks()
            is Result.Failure -> if (activeUrl != null) LinkPreviewState.forLinksWithNoPreview(activeUrl, result.failure) else LinkPreviewState.forNoLinks()
          }

          setLinkPreviewState(linkPreviewState)
        }
      }
  }

  private fun setLinkPreviewState(linkPreviewState: LinkPreviewState) {
    linkPreviewStateStore.update { cleanseState(linkPreviewState) }
  }

  private fun cleanseState(linkPreviewState: LinkPreviewState): LinkPreviewState {
    if (enabled) {
      return linkPreviewState
    }

    if (enablePlaceholder) {
      return linkPreviewState
        .linkPreview
        .map { LinkPreviewState.forLinksWithNoPreview(it.url, LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE) }
        .orElse(linkPreviewState)
    }

    return LinkPreviewState.forNoLinks()
  }
}
