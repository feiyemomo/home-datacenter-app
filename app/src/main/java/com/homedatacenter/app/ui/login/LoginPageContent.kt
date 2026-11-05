package com.homedatacenter.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
private val CyanAccent = Color(0xFF38BDF8)
private val ErrorRed = Color(0xFFEF4444)

@Composable
fun LoginPageContent(
    userId: String,
    onUserIdChange: (String) -> Unit,
    accessKey: String,
    onAccessKeyChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onLoginClick: () -> Unit
) {
    val tokens = GlassTheme.tokens
    val isLight = tokens.isLight
    val vibrantAccent = if (isLight) Color(0xFF0284C7) else CyanAccent
    val errorColor = if (isLight) Color(0xFFDC2626) else ErrorRed

    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Brand Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(vibrantAccent.copy(alpha = if (isLight) 0.16f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_dashboard),
                    contentDescription = "Logo",
                    tint = vibrantAccent,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "家庭数据中心",
                color = tokens.textColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "请输入凭据以连接您的家庭私有 NAS 网关",
                color = tokens.textSecondaryColor,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    OutlinedTextField(
                        value = userId,
                        onValueChange = onUserIdChange,
                        label = { Text("用户 ID", color = tokens.textSecondaryColor) },
                        placeholder = { Text("如 1", color = tokens.textSecondaryColor.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = tokens.textColor,
                            unfocusedTextColor = tokens.textColor,
                            focusedContainerColor = tokens.surfaceColor.copy(alpha = if (isLight) 0.5f else 0.10f),
                            unfocusedContainerColor = tokens.surfaceColor.copy(alpha = if (isLight) 0.35f else 0.05f),
                            focusedIndicatorColor = vibrantAccent,
                            unfocusedIndicatorColor = if (isLight) Color(0x33000000) else Color(0x33FFFFFF),
                            cursorColor = vibrantAccent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = accessKey,
                        onValueChange = onAccessKeyChange,
                        label = { Text("访问密钥 (Access Key)", color = tokens.textSecondaryColor) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!isLoading) onLoginClick()
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                                    ),
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    tint = tokens.textSecondaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = tokens.textColor,
                            unfocusedTextColor = tokens.textColor,
                            focusedContainerColor = tokens.surfaceColor.copy(alpha = if (isLight) 0.5f else 0.10f),
                            unfocusedContainerColor = tokens.surfaceColor.copy(alpha = if (isLight) 0.35f else 0.05f),
                            focusedIndicatorColor = vibrantAccent,
                            unfocusedIndicatorColor = if (isLight) Color(0x33000000) else Color(0x33FFFFFF),
                            cursorColor = vibrantAccent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = errorMessage,
                            color = errorColor,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = vibrantAccent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        GlassActionButton(
                            text = "登 录",
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            cornerRadius = 16.dp,
                            onClick = onLoginClick
                        )
                    }
                }
            }
        }
    }
}
