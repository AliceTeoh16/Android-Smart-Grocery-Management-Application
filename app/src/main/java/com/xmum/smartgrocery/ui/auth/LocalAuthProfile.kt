package com.xmum.smartgrocery.ui.auth

data class LocalAuthProfile(val signedIn: Boolean, val email: String, val displayName: String, val userId: String? = null)
