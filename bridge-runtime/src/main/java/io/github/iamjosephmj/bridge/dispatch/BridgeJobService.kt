package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobParameters
import android.app.job.JobService

class BridgeJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean = false
    override fun onStopJob(params: JobParameters): Boolean = false
}
