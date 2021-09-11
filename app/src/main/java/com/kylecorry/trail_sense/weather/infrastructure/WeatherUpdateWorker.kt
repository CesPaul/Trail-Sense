package com.kylecorry.trail_sense.weather.infrastructure

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kylecorry.andromeda.core.system.Wakelocks
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.andromeda.jobs.ITaskScheduler
import com.kylecorry.andromeda.jobs.TaskSchedulerFactory
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.weather.infrastructure.commands.MonitorWeatherCommand
import java.time.Duration
import java.time.LocalDateTime

class WeatherUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val wakelock = Wakelocks.get(applicationContext, WAKELOCK_TAG)
        tryOrNothing {
            wakelock?.acquire(Duration.ofSeconds(15).toMillis())
        }
        Log.d(javaClass.simpleName, "Started")
        try {
            MonitorWeatherCommand(applicationContext).execute()
        } catch (e: Exception) {
            throw e
        } finally {
            val frequency = UserPreferences(applicationContext).weather.weatherUpdateFrequency
            scheduler(applicationContext).schedule(frequency)
            Log.d(
                javaClass.simpleName,
                "Scheduled next run at ${LocalDateTime.now().plus(frequency)}"
            )
            wakelock?.release()
        }
        return Result.success()
    }

    companion object {

        private const val WAKELOCK_TAG = "com.kylecorry.trail_sense.WeatherUpdateWorker:wakelock"

        fun scheduler(context: Context): ITaskScheduler {
            return TaskSchedulerFactory(context).deferrable(
                WeatherUpdateWorker::class.java,
                2387092
            )
        }
    }

}