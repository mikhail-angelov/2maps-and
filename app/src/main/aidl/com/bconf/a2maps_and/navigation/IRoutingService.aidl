package com.bconf.a2maps_and.navigation;
import android.os.Bundle;

interface IRoutingService {
    Bundle calculate(long requestId, double fromLat, double fromLon, double toLat, double toLon, int alternative);
    void cancel(long requestId);
}
