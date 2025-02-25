package com.tari.android.wallet.ui.fragment.settings.backup.backupSettings

sealed class BackupSettingsNavigation {
    object ToWalletBackupWithRecoveryPhrase : BackupSettingsNavigation()

    object ToChangePassword : BackupSettingsNavigation()

    object ToConfirmPassword : BackupSettingsNavigation()
}