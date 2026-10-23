package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientMetadataTransportTest {
    @Test
    fun rawConfigSurvivesAParserFailureAndIsReusedAfterTransportRestart() {
        Fixture().use { fixture ->
            val raw = "build-uid = unsupported_product\n".toByteArray()
            val key = md5(raw)
            var requests = 0
            val first = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                requests++
                assertEquals(configUrl(key), url)
                FakeConnection(HttpURLConnection.HTTP_OK, raw)
            }

            val cached = first.config(key, { false }, {})
            assertThrows(IllegalArgumentException::class.java) {
                ForeverTactConfigs.build(raw, fingerprint(key))
            }
            assertTrue(cached.isFile)
            assertFalse("verified raw config cache is immutable", cached.canWrite())
            val restarted = ForeverClientMetadataTransport(fixture.cacheRoot) {
                throw AssertionError("verified config must be reused without network")
            }
            assertEquals(cached.canonicalFile, restarted.config(key, { false }, {}).canonicalFile)
            assertEquals(1, requests)
        }
    }

    @Test
    fun verifiesAndCachesMetadataRawAndDecodedByEKeyCKeyAndSizes() {
        Fixture().use { fixture ->
            val objectData = objectData()
            var requests = 0
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                requests++
                assertEquals(objectUrl(objectData.ekey), url)
                FakeConnection(HttpURLConnection.HTTP_OK, objectData.raw)
            }

            val rawFile = transport.objectRaw(objectData.reference, { false }, {})
            val decodedFile = transport.decoded(objectData.reference, { false }, {})

            assertEquals(objectData.raw.toList(), rawFile.readBytes().toList())
            assertArrayEquals(objectData.decoded, decodedFile.readBytes())
            assertEquals(1, requests)
            assertFalse(rawFile.canWrite())
            assertFalse(decodedFile.canWrite())
        }
    }

    @Test
    fun verifiesChunkedEKeyFromBlteHeaderAndTableNotTheWholeObjectHash() {
        Fixture().use { fixture ->
            val decoded = "chunked metadata object".toByteArray()
            val modeAndPayload = byteArrayOf('N'.code.toByte()) + decoded
            val header = ByteArray(36)
            byteArrayOf(0x42, 0x4c, 0x54, 0x45).copyInto(header)
            putU32be(header, 4, header.size.toLong())
            header[8] = 0x0f
            header[11] = 1
            putU32be(header, 12, modeAndPayload.size.toLong())
            putU32be(header, 16, decoded.size.toLong())
            md5Bytes(modeAndPayload).copyInto(header, 20)
            val raw = header + modeAndPayload
            val ekey = md5(header)
            assertFalse("chunked EKey is not the whole-object MD5", ekey == md5(raw))
            val reference = ForeverTactMetadata.ObjectReference("encoding", md5(decoded), ekey, raw.size.toLong(), decoded.size.toLong())
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                assertEquals(objectUrl(ekey), url)
                FakeConnection(HttpURLConnection.HTTP_OK, raw)
            }

            val cached = transport.objectRaw(reference, { false }, {})

            assertArrayEquals(raw, cached.readBytes())
        }
    }

    @Test
    fun cancellationKeepsAnOwnedPartialAndResumesTheExactRemainingRange() {
        Fixture().use { fixture ->
            val objectData = objectData(ByteArray(200_000) { (it * 31).toByte() })
            val part = File(fixture.cacheRoot, "raw/${objectData.ekey.take(2)}/${objectData.ekey}.blte.part")
            var isCancelled = false
            var requests = 0
            var rangedConnection: FakeConnection? = null
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                requests++
                assertEquals(objectUrl(objectData.ekey), url)
                if (requests == 1) {
                    FakeConnection(HttpURLConnection.HTTP_OK, objectData.raw)
                } else {
                    val offset = part.length().toInt()
                    FakeConnection(
                        HttpURLConnection.HTTP_PARTIAL,
                        objectData.raw.copyOfRange(offset, objectData.raw.size),
                        contentRange = "bytes $offset-${objectData.raw.lastIndex}/${objectData.raw.size}",
                    ).also { rangedConnection = it }
                }
            }

            assertThrows(CancellationException::class.java) {
                transport.objectRaw(objectData.reference, { isCancelled }) { progress ->
                    if (progress >= 65_536) isCancelled = true
                }
            }
            val offset = part.length()
            assertTrue(offset in 1 until objectData.raw.size.toLong())
            assertTrue(File(part.parentFile, "${part.name}.owner").isFile)

            isCancelled = false
            val raw = transport.objectRaw(objectData.reference, { false }, {})
            assertEquals("bytes=$offset-${objectData.raw.size - 1}", rangedConnection!!.requestedRange)
            assertArrayEquals(objectData.raw, raw.readBytes())
            assertEquals(2, requests)
        }
    }

    @Test
    fun rejectsWrongCKeyWithoutPublishingRawAndRejectsPayloadRolesOrUntrustedPaths() {
        Fixture().use { fixture ->
            val data = objectData()
            val wrong = data.reference.copy(contentKey = "0".repeat(32))
            var requests = 0
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) { _ ->
                requests++
                FakeConnection(HttpURLConnection.HTTP_OK, data.raw)
            }
            assertThrows(IllegalArgumentException::class.java) { transport.objectRaw(wrong, { false }, {}) }
            assertFalse(File(fixture.cacheRoot, "raw/${data.ekey.take(2)}/${data.ekey}.blte").exists())
            assertFalse(File(fixture.cacheRoot, "raw/${data.ekey.take(2)}/${data.ekey}.blte.part").exists())
            assertThrows(IllegalArgumentException::class.java) { transport.config("https://evil.example/config", { false }, {}) }
            assertThrows(IllegalArgumentException::class.java) {
                transport.objectRaw(data.reference.copy(role = "payload"), { false }, {})
            }
            assertEquals(1, requests)

            val outside = File(fixture.root, "outside").also { it.writeText("keep") }
            val otherEKey = "1".repeat(32)
            val prefixDir = File(fixture.cacheRoot, "raw/${otherEKey.take(2)}").also { it.mkdirs() }
            val part = File(prefixDir, "$otherEKey.blte.part")
            Files.createSymbolicLink(part.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) {
                transport.objectRaw(data.reference.copy(encodingKey = otherEKey), { false }, {})
            }
            assertEquals("keep", outside.readText())
        }
    }

    @Test
    fun rawIndexUsesItsOfficialIndexUrlAndOnlyReusesBytesWithItsReceipt() {
        Fixture().use { fixture ->
            val key = "12abcdef".repeat(4)
            val raw = "raw index bytes are not the archive identity".toByteArray()
            assertFalse(md5(raw) == key)
            var requests = 0
            val first = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                requests++
                assertEquals(indexUrl(key), url)
                FakeConnection(HttpURLConnection.HTTP_OK, raw)
            }
            val cached = first.index(key, { false }, {})
            assertArrayEquals(raw, cached.readBytes())
            val restarted = ForeverClientMetadataTransport(fixture.cacheRoot) {
                throw AssertionError("raw index with a valid receipt must be reused")
            }
            assertEquals(cached.canonicalFile, restarted.index(key, { false }, {}).canonicalFile)
            assertEquals(1, requests)
        }
    }

    @Test
    fun configResponseResumesFromTheDurablePrefixAndRejectsWrongRange() {
        Fixture().use { fixture ->
            val raw = "config payload that can be ranged".toByteArray()
            val key = md5(raw)
            var requests = 0
            var latest: FakeConnection? = null
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                assertEquals(configUrl(key), url)
                requests++
                if (requests == 1) {
                    FakeConnection(HttpURLConnection.HTTP_OK, raw.copyOfRange(0, 8), declaredLength = raw.size.toLong())
                } else {
                    val offset = 8
                    FakeConnection(
                        HttpURLConnection.HTTP_PARTIAL,
                        raw.copyOfRange(offset, raw.size),
                        contentRange = "bytes $offset-${raw.lastIndex}/${raw.size}",
                    ).also { latest = it }
                }
            }

            assertThrows(Exception::class.java) { transport.config(key, { false }, {}) }
            val cached = transport.config(key, { false }, {})
            assertArrayEquals(raw, cached.readBytes())
            assertEquals("bytes=8-${raw.size - 1}", latest!!.requestedRange)
            assertEquals(2, requests)
        }
    }

    @Test
    fun recoversDurableOwnerUpdateAndResumesAtTheRetainedPrefix() {
        Fixture().use { fixture ->
            val raw = "range checkpoint survives a crash between owner update write and rename".toByteArray()
            val key = md5(raw)
            val part = File(fixture.cacheRoot, "config/${key.take(2)}/$key.config.part")
            val update = File(part.parentFile, "${part.name}.owner.update")
            var requests = 0
            var checkpointWrites = 0
            val rangeConnections = mutableListOf<FakeConnection>()
            val interrupted = ForeverClientMetadataTransport(
                fixture.cacheRoot,
                { url ->
                    assertEquals(configUrl(key), url)
                    requests++
                    when (requests) {
                        1 -> FakeConnection(HttpURLConnection.HTTP_OK, raw.copyOfRange(0, 8), declaredLength = raw.size.toLong())
                        else -> {
                            val offset = part.length().toInt()
                            FakeConnection(
                                HttpURLConnection.HTTP_PARTIAL,
                                raw.copyOfRange(offset, raw.size),
                                contentRange = "bytes $offset-${raw.lastIndex}/${raw.size}",
                            ).also { rangeConnections += it }
                        }
                    }
                },
                { point ->
                    if (point == MetadataTransportFaultPoint.PARTIAL_OWNER_UPDATE_PERSISTED && ++checkpointWrites == 2) {
                        throw SimulatedProcessDeath()
                    }
                },
            )

            assertThrows(IOException::class.java) { interrupted.config(key, { false }, {}) }
            assertEquals(8L, part.length())
            assertThrows(SimulatedProcessDeath::class.java) { interrupted.config(key, { false }, {}) }
            assertEquals(8L, part.length())
            assertTrue("durable update marker survives the simulated crash", update.isFile)
            assertTrue(File(part.parentFile, "${part.name}.owner").isFile)

            val restarted = ForeverClientMetadataTransport(fixture.cacheRoot) { url ->
                assertEquals(configUrl(key), url)
                requests++
                val offset = part.length().toInt()
                FakeConnection(
                    HttpURLConnection.HTTP_PARTIAL,
                    raw.copyOfRange(offset, raw.size),
                    contentRange = "bytes $offset-${raw.lastIndex}/${raw.size}",
                ).also { rangeConnections += it }
            }
            assertArrayEquals(raw, restarted.config(key, { false }, {}).readBytes())
            assertEquals(3, requests)
            assertEquals(listOf("bytes=8-${raw.size - 1}", "bytes=8-${raw.size - 1}"), rangeConnections.map { it.requestedRange })
            assertFalse(update.exists())
            assertFalse(File(part.parentFile, "${part.name}.owner").exists())
        }
    }

    @Test
    fun refusesAndPreservesAnOwnerUpdateWhoseRoleIdentityChanged() {
        Fixture().use { fixture ->
            val raw = "preserve untrusted owner update state".toByteArray()
            val key = md5(raw)
            val part = File(fixture.cacheRoot, "config/${key.take(2)}/$key.config.part")
            val update = File(part.parentFile, "${part.name}.owner.update")
            var requests = 0
            var checkpoints = 0
            val interrupted = ForeverClientMetadataTransport(
                fixture.cacheRoot,
                { _ ->
                    requests++
                    if (requests == 1) {
                        FakeConnection(HttpURLConnection.HTTP_OK, raw.copyOfRange(0, 8), declaredLength = raw.size.toLong())
                    } else {
                        val offset = part.length().toInt()
                        FakeConnection(
                            HttpURLConnection.HTTP_PARTIAL,
                            raw.copyOfRange(offset, raw.size),
                            contentRange = "bytes $offset-${raw.lastIndex}/${raw.size}",
                        )
                    }
                },
                { point ->
                    if (point == MetadataTransportFaultPoint.PARTIAL_OWNER_UPDATE_PERSISTED && ++checkpoints == 2) {
                        throw SimulatedProcessDeath()
                    }
                },
            )
            assertThrows(IOException::class.java) { interrupted.config(key, { false }, {}) }
            assertThrows(SimulatedProcessDeath::class.java) { interrupted.config(key, { false }, {}) }
            val retainedPrefix = part.readBytes()
            val ownerToken = File(fixture.cacheRoot, ".forever-tact-owner").readLines().last()
            val invalidUpdate = "$ownerToken\nindex|$key|*\n${raw.size}".toByteArray()
            update.writeBytes(invalidUpdate)

            val restarted = ForeverClientMetadataTransport(fixture.cacheRoot) {
                throw AssertionError("untrusted owner update must fail before network")
            }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                restarted.config(key, { false }, {})
            }
            assertTrue(failure.message.orEmpty().contains("identity"))
            assertArrayEquals(retainedPrefix, part.readBytes())
            assertArrayEquals(invalidUpdate, update.readBytes())
            assertEquals(2, requests)
        }
    }

    @Test
    fun recoversIndexPublicationAtEveryDurableOwnershipBoundaryWithoutRefetching() {
        val key = "98abcdef".repeat(4)
        val raw = "source-authenticated raw index; downstream parser still validates structure".toByteArray()
        val faultPoints = listOf(
            MetadataTransportFaultPoint.INDEX_PROOF_PERSISTED,
            MetadataTransportFaultPoint.INDEX_TARGET_PUBLISHED,
            MetadataTransportFaultPoint.INDEX_PART_OWNER_REMOVED,
            MetadataTransportFaultPoint.INDEX_RECEIPT_PUBLISHED,
        )
        for (point in faultPoints) {
            Fixture().use { fixture ->
                var requests = 0
                val interrupted = ForeverClientMetadataTransport(
                    fixture.cacheRoot,
                    { url ->
                        requests++
                        assertEquals(indexUrl(key), url)
                        FakeConnection(HttpURLConnection.HTTP_OK, raw)
                    },
                    { reached -> if (reached == point) throw SimulatedProcessDeath() },
                )
                assertThrows("fault point $point", SimulatedProcessDeath::class.java) {
                    interrupted.index(key, { false }, {})
                }

                val restarted = ForeverClientMetadataTransport(fixture.cacheRoot) {
                    throw AssertionError("owner-bound index publication must recover without another request at $point")
                }
                val recovered = restarted.index(key, { false }, {})
                assertArrayEquals("fault point $point", raw, recovered.readBytes())
                assertEquals("fault point $point", 1, requests)
                assertTrue("final source receipt exists at $point", File(recovered.parentFile, "${recovered.name}.receipt").isFile)
                assertFalse("pending proof is consumed at $point", File(recovered.parentFile, "${recovered.name}.publish").exists())
                val part = File(recovered.parentFile, "${recovered.name}.part")
                assertFalse("partial bytes are consumed at $point", part.exists())
                assertFalse("partial owner is finalized at $point", File(recovered.parentFile, "${part.name}.owner").exists())
            }
        }
    }

    @Test
    fun refusesToAdoptAnIndexNamedFileWithoutAnOwnerBoundProof() {
        Fixture().use { fixture ->
            val key = "ab123456".repeat(4)
            val foreignBytes = "foreign preexisting index file".toByteArray()
            ForeverClientMetadataTransport(fixture.cacheRoot) { throw AssertionError("constructor must not request network") }
            val target = File(fixture.cacheRoot, "index/${key.take(2)}/$key.index").apply {
                parentFile!!.mkdirs()
                writeBytes(foreignBytes)
            }
            var requests = 0
            val transport = ForeverClientMetadataTransport(fixture.cacheRoot) {
                requests++
                throw AssertionError("unproven filename must fail before network")
            }

            val failure = assertThrows(IllegalStateException::class.java) { transport.index(key, { false }, {}) }

            assertTrue(failure.message.orEmpty().contains("no owner-bound receipt"))
            assertArrayEquals(foreignBytes, target.readBytes())
            assertFalse(File(target.parentFile, "${target.name}.receipt").exists())
            assertFalse(File(target.parentFile, "${target.name}.publish").exists())
            assertEquals(0, requests)
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("tact-metadata-transport-").toFile()
        val cacheRoot = File(root, "metadata-cache")
        override fun close() { root.deleteRecursively() }
    }

    private class SimulatedProcessDeath : RuntimeException("simulated process death")

    private data class Encoded(
        val raw: ByteArray,
        val decoded: ByteArray,
        val ekey: String,
        val reference: ForeverTactMetadata.ObjectReference,
    )

    private fun objectData(decoded: ByteArray = "metadata-fixture".toByteArray()): Encoded {
        val raw = byteArrayOf(0x42, 0x4c, 0x54, 0x45, 0, 0, 0, 0, 'N'.code.toByte()) + decoded
        val ekey = md5(raw)
        return Encoded(
            raw,
            decoded,
            ekey,
            ForeverTactMetadata.ObjectReference("install", md5(decoded), ekey, raw.size.toLong(), decoded.size.toLong()),
        )
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val contentRange: String? = null,
        private val declaredLength: Long = body.size.toLong(),
    ) : HttpURLConnection(URL("https://us.cdn.blizzard.com/")) {
        var requestedRange: String? = null
            private set

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = declaredLength
        override fun getContentEncoding(): String? = null
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String): String? = when {
            name.equals("Content-Range", ignoreCase = true) -> contentRange
            else -> null
        }

        override fun setRequestProperty(key: String, value: String) {
            super.setRequestProperty(key, value)
            if (key.equals("Range", ignoreCase = true)) requestedRange = value
        }
    }

    companion object {
        private fun fingerprint(hash: String) = ForeverClientUpdateCheck.Fingerprint(
            "wow_classic_beta", "1.60.1.70425", hash, "f".repeat(32), 70_425,
        )

        private fun configUrl(hash: String) = "https://us.cdn.blizzard.com/tpr/wow/config/${hash.take(2)}/${hash.substring(2, 4)}/$hash"
        private fun objectUrl(hash: String) = "https://us.cdn.blizzard.com/tpr/wow/data/${hash.take(2)}/${hash.substring(2, 4)}/$hash"
        private fun indexUrl(hash: String) = "https://us.cdn.blizzard.com/tpr/wow/data/${hash.take(2)}/${hash.substring(2, 4)}/$hash.index"
        private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).hex()
        private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
        private fun putU32be(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
