package app.voicereader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmTest {

    private fun epub(encryptionXml: String?): File = File.createTempFile("test", ".epub").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray())
            if (encryptionXml != null) {
                zip.putNextEntry(ZipEntry("META-INF/encryption.xml")); zip.write(encryptionXml.toByteArray())
            }
        }
    }

    private fun encryption(algorithm: String) =
        """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
          <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/></enc:EncryptedData></encryption>"""

    @Test fun plainEpubIsFree() = assertFalse(hasUnknownDrm(epub(null)))

    @Test fun fontObfuscationIsFree() {
        assertFalse(hasUnknownDrm(epub(encryption("http://www.idpf.org/2008/embedding"))))
        assertFalse(hasUnknownDrm(epub(encryption("http://ns.adobe.com/pdf/enc#RC"))))
    }

    @Test fun aesIsDrm() = assertTrue(hasUnknownDrm(epub(encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc"))))
}
