package com.example.beskar.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.beskar.util.Logger;

public class EmailReportWorker extends Worker {
    public static final String TAG_EMAIL_REPORT = "TAG_EMAIL_REPORT";

    private Context context;

    public EmailReportWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
        context = appContext;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Logger.debug("Running email report worker task...");
            SendEmailService.getInstance(context).sendEmail();
            return Result.success();
        } catch (Exception e) {
            e.printStackTrace();
            Logger.error("Error sending email. Message: " + e.getMessage());
            return Result.failure();
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        Logger.debug("onStopped called for EmailReportWorker");
    }
}
