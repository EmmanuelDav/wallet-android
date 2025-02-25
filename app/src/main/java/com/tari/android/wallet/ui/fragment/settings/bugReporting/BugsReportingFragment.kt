package com.tari.android.wallet.ui.fragment.settings.bugReporting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.tari.android.wallet.databinding.FragmentBugsReportingBinding
import com.tari.android.wallet.ui.common.CommonFragment
import com.tari.android.wallet.ui.fragment.settings.logs.activity.DebugActivity
import com.tari.android.wallet.ui.fragment.settings.logs.activity.DebugNavigation

class BugsReportingFragment : CommonFragment<FragmentBugsReportingBinding, BugsReportingViewModel>() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentBugsReportingBinding.inflate(inflater, container, false).also { ui = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewModel: BugsReportingViewModel by viewModels()
        bindViewModel(viewModel)

        setupUI()
        observeUI()
    }

    private fun setupUI() = with(ui) {
        backCtaView.setOnClickListener { requireActivity().onBackPressed() }
        sendButton.setOnClickListener {
            viewModel.send(
                nameEditText.text?.toString().orEmpty(),
                emailEditText.text?.toString().orEmpty(),
                bugDescription.text?.toString().orEmpty()
            )
        }
        viewLogsButton.setOnClickListener { (requireActivity() as? DebugActivity)?.navigate(DebugNavigation.Logs, rooted = false) }
    }

    private fun observeUI() = with(viewModel) {
    }
}