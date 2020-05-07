/**
 * Copyright 2020 The Tari Project
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of
 * its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tari.android.wallet.ui.fragment.send

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import butterknife.BindString
import butterknife.ButterKnife
import com.daasuu.ei.Ease
import com.daasuu.ei.EasingInterpolator
import com.orhanobut.logger.Logger
import com.tari.android.wallet.R
import com.tari.android.wallet.databinding.FragmentFinalizeSendTxBinding
import com.tari.android.wallet.event.Event
import com.tari.android.wallet.event.EventBus
import com.tari.android.wallet.infrastructure.Tracker
import com.tari.android.wallet.model.*
import com.tari.android.wallet.network.NetworkConnectionState
import com.tari.android.wallet.service.TariWalletService
import com.tari.android.wallet.tor.TorBootstrapStatus
import com.tari.android.wallet.tor.TorProxyState
import com.tari.android.wallet.ui.extension.appComponent
import com.tari.android.wallet.ui.extension.invisible
import com.tari.android.wallet.ui.extension.visible
import com.tari.android.wallet.ui.util.UiUtil.getResourceUri
import com.tari.android.wallet.util.Constants
import org.joda.time.DateTime
import org.joda.time.Seconds
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Displays the successful outgoing transaction animation.
 *
 * @author The Tari Development Team
 */
class FinalizeSendTxFragment(private val walletService: TariWalletService) : Fragment() {

    enum class FailureReason {
        NETWORK_CONNECTION_ERROR,
        BASE_NODE_CONNECTION_ERROR,
        SEND_ERROR;
    }

    private enum class Step {
        CONNECTION_CHECK,
        DISCOVERY,
        SENT;

        /**
         * The 2-line text that gets displayed during the step.
         */
        lateinit var descLine1: String
        lateinit var descLine2: String
    }

    @BindString(R.string.finalize_send_tx_sending_step_1_desc_line_1)
    lateinit var step1DescriptionLine1: String

    @BindString(R.string.finalize_send_tx_sending_step_1_desc_line_2)
    lateinit var step1DescriptionLine2: String

    @BindString(R.string.finalize_send_tx_sending_step_2_desc_line_1)
    lateinit var step2DescriptionLine1: String

    @BindString(R.string.finalize_send_tx_sending_step_2_desc_line_2)
    lateinit var step2DescriptionLine2: String

    @BindString(R.string.finalize_send_tx_sending_step_3_desc_line_1)
    lateinit var step3DescriptionLine1: String

    @BindString(R.string.finalize_send_tx_sending_step_3_desc_line_2)
    lateinit var step3DescriptionLine2: String

    @Inject
    lateinit var tracker: Tracker

    /**
     * Tx properties.
     */
    private lateinit var recipientUser: User
    private lateinit var amount: MicroTari
    private lateinit var fee: MicroTari
    private lateinit var note: String

    private lateinit var listenerWR: WeakReference<Listener>
    private val wr = WeakReference(this)
    private val lottieAnimationPauseProgress = 0.3f

    private var sentTxId: TxId? = null

    private var currentStep = Step.CONNECTION_CHECK
    private val maxProgress = 100
    // progress from 0-to-max and otherwise both increment this field
    private var progressAnimationToggleCount = 0
    private val progressBarFillDurationMs = 850L
    private var switchToNextProgressStateOnProgressAnimComplete = false
    private var baseNodeSyncCurrentRetryCount = 0
    private var baseNodeSyncMaxRetryCount = 3
    private var progressAnim: ValueAnimator? = null
    private val connectionTimeoutSecs = 20
    private lateinit var connectionCheckStartTime: DateTime

    private var failureReason: FailureReason? = null

    /**
     * Both send methods have to fail to arrive at the judgement that the send has failed.
     */
    private var directSendHasFailed = false
    private var storeAndForwardHasFailed = false

    private var _ui: FragmentFinalizeSendTxBinding? = null
    private val ui get() = _ui!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? =
        FragmentFinalizeSendTxBinding.inflate(inflater, container, false).also { _ui = it }.root

    override fun onDestroyView() {
        super.onDestroyView()
        ui.lottieAnimationView.removeAllAnimatorListeners()
        _ui = null
        EventBus.unsubscribe(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FinalizeSendTxFragmentVisitor.visit(this, view)
        // get tx properties
        recipientUser = arguments!!.getParcelable("recipientUser")!!
        amount = arguments!!.getParcelable("amount")!!
        fee = arguments!!.getParcelable("fee")!!
        note = arguments!!.getString("note")!!

        ui.lottieAnimationView.setMaxProgress(lottieAnimationPauseProgress)

        Step.CONNECTION_CHECK.descLine1 = step1DescriptionLine1
        Step.CONNECTION_CHECK.descLine2 = step1DescriptionLine2
        Step.DISCOVERY.descLine1 = step2DescriptionLine1
        Step.DISCOVERY.descLine2 = step2DescriptionLine2
        Step.SENT.descLine1 = step3DescriptionLine1
        Step.SENT.descLine2 = step3DescriptionLine2

        ui.infoLine1TextView.invisible()
        ui.infoLine2TextView.invisible()
        currentStep = Step.CONNECTION_CHECK
        ui.step1ProgressBarContainerView.alpha = 0f
        ui.step1ProgressBar.visible()
        ui.step1ProgressBar.progress = 0
        ui.step1ProgressBar.max = maxProgress
        ui.step2ProgressBarContainerView.alpha = 0f
        ui.step2ProgressBar.invisible()
        ui.step2ProgressBar.progress = 0
        ui.step2ProgressBar.max = maxProgress
        ui.step3ProgressBarContainerView.alpha = 0f
        ui.step3ProgressBar.invisible()
        ui.step2ProgressBar.progress = 0
        ui.step2ProgressBar.max = maxProgress
        ui.rootView.postDelayed(
            {
                wr.get()?.fadeInProgressBarContainers()
                wr.get()?.ui?.lottieAnimationView?.playAnimation()
                wr.get()?.playCurrentStepTextAppearAnimation()
            },
            Constants.UI.FinalizeSendTx.lottieAnimStartDelayMs
        )

        listenerWR.get()?.onSendTxStarted(this)

        // start checking network connection
        connectionCheckStartTime = DateTime.now()
        checkConnectionStatus()

        subscribeToEventBus()
        tracker.screen(path = "/home/send_tari/finalize", title = "Send Tari - Finalize")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listenerWR = WeakReference(context as Listener)
    }

    override fun onStart() {
        super.onStart()
        val mActivity = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ui.backgroundAnimationVideoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        }
        ui.backgroundAnimationVideoView.setVideoURI(mActivity.getResourceUri(R.raw.sending_background))
        ui.backgroundAnimationVideoView.setOnPreparedListener { mp -> mp.isLooping = true }
        ui.backgroundAnimationVideoView.start()
    }

    override fun onStop() {
        ui.backgroundAnimationVideoView.stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        EventBus.unsubscribe(this)
        super.onDestroy()
    }

    private fun subscribeToEventBus() {
        EventBus.subscribe<Event.Wallet.DirectSendResult>(this) { event ->
            wr.get()?.ui?.rootView?.post {
                wr.get()?.onDirectSendResult(event.txId, event.success)
            }
        }
        EventBus.subscribe<Event.Wallet.StoreAndForwardSendResult>(this) { event ->
            wr.get()?.ui?.rootView?.post {
                wr.get()?.onStoreAndForwardSendResult(event.txId, event.success)
            }
        }
    }

    private fun playCurrentStepTextAppearAnimation() {
        // line 1
        ui.infoLine1TextView.text = currentStep.descLine1
        ui.infoLine1TextView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        ui.infoLine1TextView.invisible()
        ui.infoLine1TextView.translationY = ui.infoLine1TextView.height.toFloat()
        ui.infoLine1TextView.alpha = 1f
        ui.infoLine1TextView.visible()
        ObjectAnimator.ofFloat(
            ui.infoLine1TextView,
            "translationY",
            ui.infoLine1TextView.height.toFloat(),
            0f
        ).apply {
            duration = Constants.UI.mediumDurationMs
            interpolator = EasingInterpolator(Ease.QUART_IN_OUT)
            startDelay = Constants.UI.FinalizeSendTx.textAppearAnimStartDelayMs
            start()
        }

        // line 2
        ui.infoLine2TextView.text = currentStep.descLine2
        ui.infoLine2TextView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        ui.infoLine2TextView.invisible()
        ui.infoLine2TextView.translationY = ui.infoLine2TextView.height.toFloat()
        ui.infoLine2TextView.alpha = 1f
        ui.infoLine2TextView.visible()
        ObjectAnimator.ofFloat(
            ui.infoLine2TextView,
            "translationY",
            ui.infoLine2TextView.height.toFloat(),
            0f
        ).apply {
            duration = Constants.UI.mediumDurationMs
            interpolator = EasingInterpolator(Ease.QUART_IN_OUT)
            startDelay =
                Constants.UI.FinalizeSendTx.textAppearAnimStartDelayMs + Constants.UI.xShortDurationMs
            start()
        }
    }

    private fun fadeInProgressBarContainers() {
        val fadeAnim = ValueAnimator.ofFloat(0f, 1f)
        fadeAnim.addUpdateListener { valueAnimator: ValueAnimator ->
            val alpha = valueAnimator.animatedValue as Float
            ui.step1ProgressBarContainerView.alpha = alpha
            ui.step2ProgressBarContainerView.alpha = alpha
            ui.step3ProgressBarContainerView.alpha = alpha
        }
        fadeAnim.duration = Constants.UI.longDurationMs
        fadeAnim.startDelay = Constants.UI.FinalizeSendTx.textAppearAnimStartDelayMs
        fadeAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                wr.get()?.animateCurrentStepProgress(isReverse = false)
            }
        })
        fadeAnim.start()
    }

    private fun animateCurrentStepProgress(isReverse: Boolean) {
        val progressBar = when (currentStep) {
            Step.CONNECTION_CHECK -> ui.step1ProgressBar
            Step.DISCOVERY -> ui.step2ProgressBar
            else -> ui.step3ProgressBar
        }
        progressBar.visible()
        progressAnim = if (isReverse) {
            ValueAnimator.ofInt(maxProgress, 0)
        } else {
            ValueAnimator.ofInt(0, maxProgress)
        }

        progressAnim!!.addUpdateListener { valueAnimator: ValueAnimator ->
            val progress = valueAnimator.animatedValue as Int
            progressBar.progress = progress
        }
        progressAnim!!.duration = progressBarFillDurationMs
        progressAnim!!.interpolator = EasingInterpolator(Ease.QUART_IN_OUT)
        progressAnim!!.startDelay = Constants.UI.xShortDurationMs
        progressAnim!!.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                progressAnimationToggleCount++
                progressAnim!!.removeAllListeners()
                if (progressAnimationToggleCount >= 3 && !isReverse) {
                    tryToProceedToTheNextStepOnProgressAnimCompletion()
                } else {
                    animateCurrentStepProgress(!isReverse)
                }
            }
        })
        progressAnim!!.start()
    }

    private fun tryToProceedToTheNextStepOnProgressAnimCompletion() {
        failureReason?.let {
            onFailure(it)
            return@tryToProceedToTheNextStepOnProgressAnimCompletion
        }
        if (switchToNextProgressStateOnProgressAnimComplete) {
            switchToNextProgressStateOnProgressAnimComplete = false
            goToNextStep()
            return
        } else if (currentStep == Step.SENT) {
            onSuccess()
            return
        }
        if (currentStep == Step.CONNECTION_CHECK) {
            val secondsElapsed = Seconds.secondsBetween(
                connectionCheckStartTime,
                DateTime.now()
            ).seconds
            if (secondsElapsed >= connectionTimeoutSecs) { // network connection timeout
                val networkConnectionState = EventBus.networkConnectionStateSubject.value
                if (networkConnectionState != NetworkConnectionState.CONNECTED) {
                    // internet connection problem
                    onFailure(FailureReason.NETWORK_CONNECTION_ERROR)
                } else {
                    // tor connection problem
                    onFailure(FailureReason.BASE_NODE_CONNECTION_ERROR)
                }
                return
            }
        }
        animateCurrentStepProgress(isReverse = true)
    }

    /**
     * Step #1 of the flow.
     */
    private fun checkConnectionStatus() {
        val networkConnectionState = EventBus.networkConnectionStateSubject.value
        val torProxyState = EventBus.torProxyStateSubject.value
        // check internet connection & whether Tor proxy is running
        if (networkConnectionState != NetworkConnectionState.CONNECTED) {
            Logger.w("Send error: not connected to the internet.")
            // either not connected or Tor proxy is not running
            failureReason = FailureReason.NETWORK_CONNECTION_ERROR
            return
        }
        // check whether Tor proxy is running
        if (torProxyState !is TorProxyState.Running) {
            Logger.w("Send error: Tor proxy is not running.")
            // either not connected or Tor proxy is not running
            failureReason = FailureReason.NETWORK_CONNECTION_ERROR
            return
        }
        // check Tor bootstrap status
        if (torProxyState.bootstrapStatus.progress < TorBootstrapStatus.maxProgress) {
            Logger.d("Tor proxy not ready - start waiting.")
            // subscribe to Tor proxy state changes - start waiting on it
            EventBus.subscribeToTorProxyState(this) { state ->
                onTorProxyStateChanged(state)
            }
            return
        }
        syncWithBaseNode()
    }

    @SuppressLint("CheckResult")
    private fun onTorProxyStateChanged(torProxyState: TorProxyState) {
        if (torProxyState is TorProxyState.Running) {
            if (torProxyState.bootstrapStatus.progress == TorBootstrapStatus.maxProgress) {
                EventBus.unsubscribeFromTorProxyState(this)
                checkConnectionStatus()
            }
        }
    }

    private fun syncWithBaseNode() {
        baseNodeSyncCurrentRetryCount++
        // sync base node
        Logger.e(
            "Try to sync with base node - retry count %d.",
            baseNodeSyncCurrentRetryCount
        )
        val walletError = WalletError()
        walletService.syncWithBaseNode(walletError)
        if (walletError.code != WalletErrorCode.NO_ERROR) {
            if (baseNodeSyncCurrentRetryCount >= baseNodeSyncMaxRetryCount) {
                failureReason = FailureReason.BASE_NODE_CONNECTION_ERROR
            } else {
                syncWithBaseNode()
            }
            return
        }
        // start waiting on base node sync status
        EventBus.subscribe<Event.Wallet.BaseNodeSyncComplete>(this) { event ->
            wr.get()?.ui?.rootView?.post {
                wr.get()?.onBaseNodeSyncCompleted(event)
            }
        }
    }

    private fun onBaseNodeSyncCompleted(event: Event.Wallet.BaseNodeSyncComplete) {
        if (!event.success) {
            if (baseNodeSyncCurrentRetryCount >= baseNodeSyncMaxRetryCount) {
                failureReason = FailureReason.BASE_NODE_CONNECTION_ERROR
            } else {
                syncWithBaseNode()
            }
            return
        }
        // base node sync success, switch to next state
        switchToNextProgressStateOnProgressAnimComplete = true
    }

    private fun fadeOutTextViews(completion: () -> Unit) {
        val alphaAnim = ValueAnimator.ofFloat(1f, 0f)
        alphaAnim.addUpdateListener { valueAnimator: ValueAnimator ->
            val alpha = valueAnimator.animatedValue as Float
            ui.infoLine1TextView.alpha = alpha
            ui.infoLine2TextView.alpha = alpha
        }
        alphaAnim.duration = Constants.UI.mediumDurationMs
        alphaAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                alphaAnim.removeAllListeners()
                completion()
            }
        })
        alphaAnim!!.start()
    }

    private fun goToNextStep() {
        when (currentStep) {
            Step.CONNECTION_CHECK -> {
                fadeOutTextViews {
                    progressAnimationToggleCount = 0
                    currentStep = Step.DISCOVERY
                    playCurrentStepTextAppearAnimation()
                    animateCurrentStepProgress(isReverse = false)
                    sendTari()
                }
            }
            Step.DISCOVERY -> {
                fadeOutTextViews {
                    progressAnimationToggleCount = 0
                    currentStep = Step.SENT
                    playCurrentStepTextAppearAnimation()
                    animateCurrentStepProgress(isReverse = false)
                }
            }
            Step.SENT -> {
                onSuccess()
            }
        }
    }

    private fun sendTari() {
        listenerWR.get()?.onSendTxStarted(this)
        val error = WalletError()
        val txId = walletService.sendTari(recipientUser, amount, fee, note, error)
        // if success, just wait for the callback to happen
        // if failed, just show the failed info & return
        if (txId == null || error.code != WalletErrorCode.NO_ERROR) {
            failureReason = FailureReason.SEND_ERROR
        } else {
            sentTxId = txId
        }
    }

    private fun onDirectSendResult(txId: TxId, success: Boolean) {
        if (sentTxId != txId) {
            Logger.d("Response received for another tx with id: ${txId.value}.")
            return
        }
        Logger.d("Direct Send completed with result: $success.")
        if (success) {
            // track event
            tracker.event(category = "Transaction", action = "Transaction Accepted - Synchronous")
            // progress state
            switchToNextProgressStateOnProgressAnimComplete = true
            EventBus.unsubscribe(this)
        } else {
            directSendHasFailed = true
            checkForCombinedFailure()
        }
    }

    private fun onStoreAndForwardSendResult(txId: TxId, success: Boolean) {
        if (sentTxId != txId) {
            Logger.d("Response received for another tx with id: ${txId.value}.")
            return
        }
        Logger.d("Store and forward send completed with result: $success.")
        if (success) {
            // track event
            tracker.event(category = "Transaction", action = "Transaction Stored")
            // progress state
            switchToNextProgressStateOnProgressAnimComplete = true
            EventBus.unsubscribe(this)
        } else {
            storeAndForwardHasFailed = true
            checkForCombinedFailure()
        }
    }

    private fun checkForCombinedFailure() {
        if (directSendHasFailed && storeAndForwardHasFailed) { // both have failed
            failureReason = FailureReason.SEND_ERROR
        }
    }

    private fun onFailure(failureReason: FailureReason) {
        progressAnim?.removeAllListeners()
        ui.lottieAnimationView.speed = -1f
        ui.lottieAnimationView.playAnimation()
        ui.lottieAnimationView.progress = lottieAnimationPauseProgress

        // track event
        val trackerEvent = when(failureReason) {
            FailureReason.NETWORK_CONNECTION_ERROR -> "Transaction Failed - Tor Issue"
            FailureReason.BASE_NODE_CONNECTION_ERROR -> "Transaction Failed - Node Issue"
            FailureReason.SEND_ERROR -> "Transaction Failed - Node Issue"
        }
        tracker.event(category = "Transaction", action = trackerEvent)

        // fade out text and progress
        val fadeOutAnim = ValueAnimator.ofFloat(1f, 0f)
        fadeOutAnim.addUpdateListener { valueAnimator: ValueAnimator ->
            val alpha = valueAnimator.animatedValue as Float
            ui.infoLine1TextView.alpha = alpha
            ui.infoLine2TextView.alpha = alpha
            ui.step1ProgressBarContainerView.alpha = alpha
            ui.step2ProgressBarContainerView.alpha = alpha
            ui.step3ProgressBarContainerView.alpha = alpha
        }
        fadeOutAnim.duration = Constants.UI.xLongDurationMs
        fadeOutAnim.interpolator = EasingInterpolator(Ease.QUART_IN_OUT)
        fadeOutAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                fadeOutAnim.removeAllListeners()
                ui.lottieAnimationView.alpha = 0f
                listenerWR.get()?.onSendTxFailure(
                    this@FinalizeSendTxFragment,
                    recipientUser,
                    amount,
                    fee,
                    note,
                    failureReason
                )
            }
        })
        fadeOutAnim.start()
    }

    private fun onSuccess() {
        ui.lottieAnimationView.setMaxProgress(1.0f)
        ui.lottieAnimationView.playAnimation()
        ui.lottieAnimationView.progress = lottieAnimationPauseProgress
        // fade out text and progress
        val fadeOutAnim = ValueAnimator.ofFloat(1f, 0f)
        fadeOutAnim.addUpdateListener { valueAnimator: ValueAnimator ->
            val alpha = valueAnimator.animatedValue as Float
            ui.infoLine1TextView.alpha = alpha
            ui.infoLine2TextView.alpha = alpha
            ui.step1ProgressBarContainerView.alpha = alpha
            ui.step2ProgressBarContainerView.alpha = alpha
            ui.step3ProgressBarContainerView.alpha = alpha
        }
        fadeOutAnim.duration = Constants.UI.longDurationMs
        fadeOutAnim.interpolator = EasingInterpolator(Ease.QUART_IN_OUT)
        fadeOutAnim.startDelay = Constants.UI.FinalizeSendTx.successfulInfoFadeOutAnimStartDelayMs
        fadeOutAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                fadeOutAnim.removeAllListeners()
                ui.lottieAnimationView.alpha = 0f
                listenerWR.get()?.onSendTxSuccessful(
                    this@FinalizeSendTxFragment,
                    recipientUser,
                    amount,
                    fee,
                    note
                )
            }
        })
        fadeOutAnim.start()
    }

    /**
     * Listener interface - to be implemented by the host activity.
     */
    interface Listener {

        fun onSendTxStarted(sourceFragment: FinalizeSendTxFragment)

        /**
         * Recipient is user.
         */
        fun onSendTxFailure(
            sourceFragment: FinalizeSendTxFragment,
            recipientUser: User,
            amount: MicroTari,
            fee: MicroTari,
            note: String,
            failureReason: FailureReason
        )

        /**
         * Recipient is user.
         */
        fun onSendTxSuccessful(
            sourceFragment: FinalizeSendTxFragment,
            recipientUser: User,
            amount: MicroTari,
            fee: MicroTari,
            note: String
        )

    }

    private object FinalizeSendTxFragmentVisitor {
        internal fun visit(fragment: FinalizeSendTxFragment, view: View) {
            fragment.requireActivity().appComponent.inject(fragment)
            ButterKnife.bind(fragment, view)
        }
    }

}
