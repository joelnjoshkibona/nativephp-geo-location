<?php

return [
    /*
     * The ongoing notification Android requires while location is being recorded in the background.
     * It is what keeps the recording alive, so it cannot be hidden. Write it so the person understands it.
     */
    'notification_title' => env('GEO_LOCATION_NOTIFICATION_TITLE', 'Recording the places you visit'),
    'notification_text' => env('GEO_LOCATION_NOTIFICATION_TEXT', 'Location stays on this phone.'),

    /*
     * Defaults for startTracking(). A fix is saved when the phone has moved `min_distance_meters`
     * and at least `interval_seconds` have passed. Fixes less accurate than `max_accuracy_meters` are dropped (0 keeps all).
     */
    'interval_seconds' => (int) env('GEO_LOCATION_INTERVAL_SECONDS', 60),
    'min_distance_meters' => (int) env('GEO_LOCATION_MIN_DISTANCE_METERS', 25),
    'max_accuracy_meters' => (int) env('GEO_LOCATION_MAX_ACCURACY_METERS', 150),
];
