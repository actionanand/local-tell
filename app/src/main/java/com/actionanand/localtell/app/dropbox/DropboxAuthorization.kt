package com.actionanand.localtell.app.dropbox

/** Pure validation for the scopes LocalTell requires before enabling future backup actions. */
internal object DropboxAuthorization {
    val requiredScopes = setOf("account_info.read", "files.metadata.read", "files.content.read", "files.content.write")

    fun hasRequiredScopes(grantedScope: String?): Boolean =
        grantedScope?.split(' ')?.filter(String::isNotBlank)?.toSet()?.containsAll(requiredScopes) == true

    fun canAcceptOAuthResult(hasCredential: Boolean, refreshToken: String?, grantedScope: String?): Boolean =
        hasCredential && !refreshToken.isNullOrBlank() && hasRequiredScopes(grantedScope)
}
