import re

with open("app/src/main/java/com/pisophone/kiosk/MainActivity.kt", "r") as f:
    content = f.read()

# Find the start of LauncherScreen
start_idx = content.find("@Composable\nfun LauncherScreen(")
if start_idx == -1:
    start_idx = content.find("@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)\n@Composable\nfun LauncherScreen(")

# The end of the file is the end of LauncherScreen
new_launcher = """@Composable
fun LauncherScreen(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
) {
    data class CleanTheme(
        val name: String,
        val bg: Color,
        val surface: Color,
        val primary: Color,
        val onPrimary: Color,
        val textPrimary: Color,
        val textSecondary: Color,
        val border: Color,
        val isDark: Boolean
    )

    val currentTheme = CleanTheme(
        name = "Obsidian Emerald",
        bg = Color(0xFF060B14),
        surface = Color(0xFF0F172A),
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF020617),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        border = Color(0xFF1E293B),
        isDark = true
    )

    @Composable
    fun TimeDateDisplay(currentTheme: CleanTheme) {
        var time by remember { mutableStateOf("") }
        var date by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            while (true) {
                val now = Date()
                time = timeFormat.format(now)
                date = dateFormat.format(now)
                delay(1000)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = time,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = currentTheme.textPrimary,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = date,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = currentTheme.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.bg)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Header Row with Website-style Branding & Clock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // PisoPhone Branding (Matches website navigation bar)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "PisoPhone",
                        tint = currentTheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Row {
                        Text(
                            text = "PISO",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = currentTheme.textPrimary
                        )
                        Text(
                            text = "PHONE",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = currentTheme.primary
                        )
                    }
                    // Status Pill
                    Surface(
                        color = currentTheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, currentTheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(currentTheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "KIOSK",
                                color = currentTheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            TimeDateDisplay(currentTheme)
        }
        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        val categories = listOf("Social Media", "Gaming", "Entertainment", "Browsing", "Shopping", "Utilities", "Other Apps")
        
        val appsByCategory = remember(apps) {
            val map = mutableMapOf<String, MutableList<AppInfo>>()
            categories.forEach { map[it] = mutableListOf() }
            
            apps.forEach { app ->
                val name = app.name.lowercase()
                val pkg = app.packageName.lowercase()
                
                val category = when {
                    pkg.contains("facebook") || pkg.contains("twitter") || pkg.contains("instagram") || pkg.contains("tiktok") || pkg.contains("snapchat") || pkg.contains("social") || pkg.contains("discord") || pkg.contains("reddit") || pkg.contains("telegram") || pkg.contains("whatsapp") || pkg.contains("messenger") || pkg.contains("viber") || name.contains("facebook") || name.contains("instagram") || name.contains("tiktok") || name.contains("messenger") -> "Social Media"
                    pkg.contains("game") || pkg.contains("unity") || pkg.contains("epic") || pkg.contains("roblox") || pkg.contains("minecraft") || pkg.contains("mobilelegends") || pkg.contains("pubg") || pkg.contains("tencent") || pkg.contains("codm") || pkg.contains("supercell") || name.contains("game") || name.contains("roblox") -> "Gaming"
                    pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("hulu") || pkg.contains("spotify") || pkg.contains("video") || pkg.contains("music") || pkg.contains("tv") || pkg.contains("media") || pkg.contains("player") || name.contains("youtube") || name.contains("netflix") || name.contains("tv") || name.contains("player") || name.contains("music") -> "Entertainment"
                    pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("opera") || pkg.contains("edge") || pkg.contains("brave") || pkg.contains("duckduckgo") || name.contains("browser") || name.contains("chrome") -> "Browsing"
                    pkg.contains("shop") || pkg.contains("amazon") || pkg.contains("ebay") || pkg.contains("lazada") || pkg.contains("shopee") || pkg.contains("zalora") || pkg.contains("shein") || pkg.contains("alibaba") || pkg.contains("aliexpress") || name.contains("shop") || name.contains("lazada") || name.contains("shopee") || name.contains("amazon") -> "Shopping"
                    pkg.contains("calc") || pkg.contains("clock") || pkg.contains("calendar") || pkg.contains("camera") || pkg.contains("gallery") || pkg.contains("settings") || pkg.contains("util") || pkg.contains("file") || pkg.contains("tools") || pkg.contains("notes") || pkg.contains("maps") || pkg.contains("weather") -> "Utilities"
                    else -> "Other Apps"
                }
                map[category]?.add(app)
            }
            map
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            categories.forEach { category ->
                val categoryApps = appsByCategory[category]
                if (!categoryApps.isNullOrEmpty()) {
                    item {
                        Text(
                            text = category,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = currentTheme.textPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categoryApps) { app ->
                                Column(
                                    modifier = Modifier
                                        .width(76.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onAppClick(app) }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (app.bitmap != null) {
                                        Image(
                                            bitmap = app.bitmap,
                                            contentDescription = app.name,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .background(currentTheme.surface, RoundedCornerShape(14.dp))
                                                .border(1.dp, currentTheme.border, RoundedCornerShape(14.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Apps,
                                                contentDescription = null,
                                                tint = currentTheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = app.name,
                                        color = currentTheme.textPrimary,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
"""

with open("app/src/main/java/com/pisophone/kiosk/MainActivity.kt", "w") as f:
    f.write(content[:start_idx] + new_launcher)

