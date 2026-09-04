package com.lagradost.cloudstream3.syncproviders

import com.pulsestream.app.syncproviders.AccountManager as RealAccountManager
import com.pulsestream.app.syncproviders.AuthData

/**
 * Compatibility shim: external .cs3 plugins compiled against the original
 * CloudStream3 expect [AccountManager] at
 * [com.lagradost.cloudstream3.syncproviders.AccountManager].
 *
 * This class provides the exact bytecode shape (abstract class + companion
 * object with the same constant names and accessor methods) while delegating
 * to the real PulseStream AccountManager.
 */
abstract class AccountManager {

    companion object {
        const val NONE_ID: Int = RealAccountManager.NONE_ID

        const val ACCOUNT_TOKEN: String = RealAccountManager.ACCOUNT_TOKEN
        const val ACCOUNT_IDS: String = RealAccountManager.ACCOUNT_IDS

        const val APP_STRING: String = RealAccountManager.APP_STRING
        const val APP_STRING_REPO: String = RealAccountManager.APP_STRING_REPO
        const val APP_STRING_PLAYER: String = RealAccountManager.APP_STRING_PLAYER
        const val APP_STRING_SEARCH: String = RealAccountManager.APP_STRING_SEARCH
        const val APP_STRING_RESUME_WATCHING: String = RealAccountManager.APP_STRING_RESUME_WATCHING
        const val APP_STRING_SHARE: String = RealAccountManager.APP_STRING_SHARE

        val malApi get() = RealAccountManager.malApi
        val kitsuApi get() = RealAccountManager.kitsuApi
        val aniListApi get() = RealAccountManager.aniListApi
        val simklApi get() = RealAccountManager.simklApi
        val localListApi get() = RealAccountManager.localListApi
        val openSubtitlesApi get() = RealAccountManager.openSubtitlesApi
        val addic7ed get() = RealAccountManager.addic7ed
        val subDlApi get() = RealAccountManager.subDlApi
        val subSourceApi get() = RealAccountManager.subSourceApi
        val animeSkipApi get() = RealAccountManager.animeSkipApi

        val allApis get() = RealAccountManager.allApis
        val syncApis get() = RealAccountManager.syncApis
        val subtitleProviders get() = RealAccountManager.subtitleProviders

        val cachedAccounts: MutableMap<String, Array<AuthData>>
            get() = RealAccountManager.cachedAccounts
        val cachedAccountIds: MutableMap<String, Int>
            get() = RealAccountManager.cachedAccountIds

        fun accounts(prefix: String): Array<AuthData> = RealAccountManager.accounts(prefix)
        fun updateAccounts(prefix: String, array: Array<AuthData>) =
            RealAccountManager.updateAccounts(prefix, array)
        fun updateAccountsId(prefix: String, id: Int) =
            RealAccountManager.updateAccountsId(prefix, id)
        fun updateAccountIds() = RealAccountManager.updateAccountIds()
        fun initMainAPI() = RealAccountManager.initMainAPI()

        fun secondsToReadable(seconds: Int, completedValue: String): String =
            RealAccountManager.secondsToReadable(seconds, completedValue)
    }
}
