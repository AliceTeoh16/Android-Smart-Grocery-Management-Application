package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.navigation

private object PantryRoutes {
    const val Auth = "pantrypal/auth"
    const val Welcome = "pantrypal/welcome"
    const val Login = "pantrypal/login"
    const val SignUp = "pantrypal/sign-up"
    const val ForgotPassword = "pantrypal/forgot-password"
    const val Home = "pantrypal/home"
}

enum class PantryEntry { Welcome, Login, Home }

/**
 * Hosted by PantryPalFragment in the app's existing MainActivity. Gradle includes compose-pages
 * as a source directory. Services and live dashboard state are supplied by the host adapter.
 * [entry] is fixed for a host instance; saved navigation state restores across configuration changes.
 * Authentication success removes the form stack. Passwords are never saved to instance state.
 */
@Composable
fun AuthNavGraph(
    homeState: HomeOverviewUiState,
    onLogin: suspend (email: String, password: String) -> AuthResult,
    onSignUp: suspend (name: String, email: String, password: String) -> AuthResult,
    onSendResetLink: suspend (email: String) -> AuthResult,
    onTermsOfService: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onHomeAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    resetLinkExpiryMinutes: Int = 30,
    entry: PantryEntry = PantryEntry.Welcome,
    resetEmailAvailable: Boolean = true,
    onConfigureSharing: () -> Unit = {},
) {
    val navigateToHome: () -> Unit = {
        navController.navigate(PantryRoutes.Home) {
            // Remove the entire authentication flow after successful authentication.
            popUpTo(PantryRoutes.Auth) { inclusive = true }
            launchSingleTop = true
        }
    }
    val navigateToLogin: () -> Unit = {
        if (!navController.popBackStack(PantryRoutes.Login, inclusive = false)) {
            navController.navigate(PantryRoutes.Login) {
                popUpTo(PantryRoutes.Welcome) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    val goBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            navController.navigate(PantryRoutes.Welcome) {
                popUpTo(PantryRoutes.Auth) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    PantryPalTheme {
        NavHost(navController = navController,
            startDestination = if (entry == PantryEntry.Home) PantryRoutes.Home else PantryRoutes.Auth,
            modifier = modifier) {
            navigation(startDestination = if (entry == PantryEntry.Login) PantryRoutes.Login else PantryRoutes.Welcome,
                route = PantryRoutes.Auth) {
                composable(PantryRoutes.Welcome) {
                    WelcomeScreen(
                        onNavigateToLogin = { navController.navigate(PantryRoutes.Login) },
                        onNavigateToSignUp = { navController.navigate(PantryRoutes.SignUp) },
                    )
                }
                composable(PantryRoutes.Login) {
                    LoginScreen(
                        onBack = goBack,
                        onNavigateToSignUp = { navController.navigate(PantryRoutes.SignUp) },
                        onNavigateToForgotPassword = { navController.navigate(PantryRoutes.ForgotPassword) },
                        onNavigateToHome = navigateToHome,
                        onLogin = onLogin,
                        onConfigureSharing = onConfigureSharing,
                    )
                }
                composable(PantryRoutes.SignUp) {
                    SignUpScreen(
                        onBack = goBack, onNavigateToLogin = navigateToLogin,
                        onNavigateToHome = navigateToHome,
                        onTermsOfService = onTermsOfService, onPrivacyPolicy = onPrivacyPolicy,
                        onSignUp = onSignUp,
                        onConfigureSharing = onConfigureSharing,
                    )
                }
                composable(PantryRoutes.ForgotPassword) {
                    ForgotPasswordScreen(
                        onBack = goBack, onNavigateToLogin = navigateToLogin,
                        onSendResetLink = onSendResetLink,
                        resetLinkExpiryMinutes = resetLinkExpiryMinutes,
                        resetEmailAvailable = resetEmailAvailable,
                    )
                }
            }
            composable(PantryRoutes.Home) {
                HomeOverviewScreen(state = homeState, onAction = onHomeAction)
            }
        }
    }
}
