package com.actionanand.localtell.app.external

import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

data class RideDestination(val latitude: Double, val longitude: Double, val label: String, val address: String)

object MapLinkBuilder {
    fun googleMapsUrl(latitude: Double, longitude: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(coordinateText(latitude, longitude), "UTF-8")}"

    fun googleMaps(latitude: Double, longitude: Double): Uri = Uri.parse(googleMapsUrl(latitude, longitude))

    fun coordinateText(latitude: Double, longitude: Double): String = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
}

object RideLinkBuilder {
    const val UBER_PACKAGE = "com.ubercab"
    const val OLA_PACKAGE = "com.olacabs.customer"
    const val RAPIDO_PACKAGE = "com.rapido.passenger"

    /** Standard Uber set-pickup link: pickup=my_location lets Uber choose the rider's pickup. */
    fun uber(destination: RideDestination): Uri = Uri.Builder()
        .scheme("uber")
        .authority("riderequest")
        .appendQueryParameter("pickup", "my_location")
        .appendQueryParameter("dropoff[latitude]", destination.latitude.toString())
        .appendQueryParameter("dropoff[longitude]", destination.longitude.toString())
        .appendQueryParameter("dropoff[nickname]", destination.label)
        .appendQueryParameter("dropoff[formatted_address]", destination.address)
        .build()
}
