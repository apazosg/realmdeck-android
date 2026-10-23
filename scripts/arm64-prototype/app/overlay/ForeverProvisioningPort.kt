package app.gamenative

import android.content.Context
import java.io.File

/** Wiring boundary for the catalog provisioner. */
interface ForeverProvisioningPort {
    data class Plan(val downloadBytes: Long, val workspaceBytes: Long)

    fun plan(context: Context): Plan

    suspend fun ensure(
        context: Context,
        installDir: File,
        cancelled: () -> Boolean,
        progress: (String) -> Unit,
    ): File
}

object ForeverProvisionerRegistry {
    @Volatile
    var implementation: ForeverProvisioningPort = object : ForeverProvisioningPort {
        override fun plan(context: Context): ForeverProvisioningPort.Plan {
            val plan = ForeverClientProvisioner.plan(context)
            return ForeverProvisioningPort.Plan(plan.downloadBytes, plan.workspaceBytes)
        }

        override suspend fun ensure(
            context: Context,
            installDir: File,
            cancelled: () -> Boolean,
            progress: (String) -> Unit,
        ): File = ForeverClientProvisioner.ensure(context, installDir, cancelled, progress)
    }
}
