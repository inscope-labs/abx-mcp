package com.inscopelabs.abxmcp.core.session

sealed class UserGesture {
    object LocalButtonPress : UserGesture()
    object NotificationAction : UserGesture()
}
