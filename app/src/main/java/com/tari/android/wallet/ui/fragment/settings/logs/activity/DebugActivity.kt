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
package com.tari.android.wallet.ui.fragment.settings.logs.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import com.tari.android.wallet.databinding.ActivityDebugBinding
import com.tari.android.wallet.ui.common.CommonActivity
import com.tari.android.wallet.ui.fragment.settings.baseNodeConfig.BaseNodeRouter
import com.tari.android.wallet.ui.fragment.settings.baseNodeConfig.addBaseNode.AddCustomBaseNodeFragment
import com.tari.android.wallet.ui.fragment.settings.bugReporting.BugsReportingFragment
import com.tari.android.wallet.ui.fragment.settings.logs.logFiles.LogFilesFragment
import com.tari.android.wallet.ui.fragment.settings.logs.logs.LogsFragment
import java.io.File

class DebugActivity : CommonActivity<ActivityDebugBinding, DebugViewModel>(), BaseNodeRouter {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ui = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(ui.root)

        val viewModel: DebugViewModel by viewModels()
        bindViewModel(viewModel)

        setContainerId(ui.navContainer.id)

        val navigationStr = intent.getStringExtra(navigation_key)
        navigate(DebugNavigation.values().firstOrNull { it.toString() == navigationStr })
    }

    fun navigate(navigation: DebugNavigation?, file: File? = null, rooted: Boolean = true) {
        when (navigation) {
            DebugNavigation.Logs -> addFragment(LogFilesFragment(), null, rooted)
            DebugNavigation.LogDetail -> addFragment(LogsFragment.getInstance(file!!), null, rooted)
            DebugNavigation.BugReport -> addFragment(BugsReportingFragment(), null, rooted)
            else -> Unit
        }
    }

    override fun toAddCustomBaseNode() = addFragment(AddCustomBaseNodeFragment())

    companion object {
        const val navigation_key = "Debug_navigation"
        const val log_file = "Logs_detail"

        fun launch(context: Context, navigation: DebugNavigation) {
            val intent = Intent(context, DebugActivity::class.java)
            intent.putExtra("Debug_navigation", navigation.toString())
            context.startActivity(intent)
        }
    }
}
