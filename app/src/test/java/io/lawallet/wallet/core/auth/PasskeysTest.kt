package io.lawallet.wallet.core.auth

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeysTest {
    private fun kind(e: Exception) = Passkeys.translate(e).kind

    @Test fun mapsByTypeNotClassName() {
        assertEquals("cancelled", kind(GetCredentialCancellationException("x")))
        assertEquals("cancelled", kind(CreateCredentialCancellationException("x")))
        assertEquals("cancelled", kind(GetPublicKeyCredentialDomException(NotAllowedError(), "timed out")))
        assertEquals("no-credential", kind(NoCredentialException("x")))
        assertEquals("duplicate", kind(CreatePublicKeyCredentialDomException(InvalidStateError(), "excluded")))
        assertEquals("rp", kind(CreatePublicKeyCredentialDomException(SecurityError(), "rp id")))
        assertEquals("unsupported", kind(CreateCredentialNoCreateOptionException("x")))
        assertEquals("failed", kind(GetCredentialUnknownException("boom")))
        assertEquals("boom", Passkeys.translate(GetCredentialUnknownException("boom")).message)
    }
}
