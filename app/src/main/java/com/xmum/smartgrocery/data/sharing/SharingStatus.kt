package com.xmum.smartgrocery.data.sharing

data class SharingStatus(val connected: Boolean = false, val syncing: Boolean = false, val message: String = "Connect your account to share across devices.")
