package com.actionanand.localtell.app.dropbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxAuthorizationTest {
    @Test fun `all configured Dropbox scopes are required`() {
        assertTrue(DropboxAuthorization.hasRequiredScopes(
            "account_info.read files.metadata.read files.content.read files.content.write",
        ))
    }

    @Test fun `missing or malformed scope result is rejected`() {
        assertFalse(DropboxAuthorization.hasRequiredScopes(null))
        assertFalse(DropboxAuthorization.hasRequiredScopes("account_info.read files.content.read files.content.write"))
        assertFalse(DropboxAuthorization.hasRequiredScopes("files.metadata.read"))
    }

    @Test fun `cancelled authorization and missing refresh credentials are rejected`() {
        val scopes = "account_info.read files.metadata.read files.content.read files.content.write"
        assertFalse(DropboxAuthorization.canAcceptOAuthResult(false, "refresh", scopes))
        assertFalse(DropboxAuthorization.canAcceptOAuthResult(true, null, scopes))
        assertFalse(DropboxAuthorization.canAcceptOAuthResult(true, "", scopes))
        assertTrue(DropboxAuthorization.canAcceptOAuthResult(true, "refresh", scopes))
    }
}
