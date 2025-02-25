package com.tari.android.wallet.ui.fragment.settings.allSettings

interface AllSettingsRouter {
    fun toAbout()

    fun toBackupSettings()

    fun toDeleteWallet()

    fun toBackgroundService()

    fun toNetworkSelection()

    fun toTorBridges()

    fun toCustomTorBridges()

    fun toBaseNodeSelection()
}