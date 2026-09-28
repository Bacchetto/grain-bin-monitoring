package com.grainbin.telemetry.alerts;

/**
 * What one run of a scheduled evaluator did.
 *
 * @param evaluated  conditions actually judged -- devices, or sensors with
 *                   enough data in both windows
 * @param detected   of those, how many had the condition present
 * @param clearsRecorded clear evaluations recorded against open alerts
 */
public record EvaluationRun(int evaluated, int detected, int clearsRecorded) {
}
