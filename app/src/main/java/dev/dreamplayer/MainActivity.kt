package dev.dreamplayer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var source: EditText
    private lateinit var subtitle: TextView
    private lateinit var mode: Spinner
    private lateinit var host: EditText
    private lateinit var share: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var domain: EditText
    private lateinit var smbPath: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var subtitleUri: Uri? = null
    private var selectedSmbCredentials: SmbCredentials? = null
    private var entries: List<SmbEntry> = emptyList()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        source.setText(uri.toString())
        selectedSmbCredentials = null
        status.text = "Selected ${displayName(uri)}"
        launchPlayer()
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        subtitleUri = uri
        subtitle.text = "Subtitle: ${displayName(uri)}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 16)
        window.navigationBarColor = Color.rgb(8, 11, 16)
        setContentView(buildUi())

        intent.data?.let {
            source.setText(it.toString())
            status.text = "Opened from another app"
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        root.addView(TextView(this).apply {
            text = "Dream Player"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Clean-room PFDM OpenXR media player MVP"
            setTextColor(Color.rgb(160, 178, 194))
        })

        root.addView(row(
            button("Choose local video & play") { pickVideo.launch(arrayOf("video/*")) },
            button("Choose subtitle") { pickSubtitle.launch(arrayOf("text/*", "application/x-subrip")) },
        ))

        source = edit("Local content URI, HTTP(S) URL, or selected SMB file")
        root.addView(source, matchWrap())
        subtitle = label("Subtitle: embedded/none")
        root.addView(subtitle)

        root.addView(row(label("Projection"), Spinner(this).also { spinner ->
            mode = spinner
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("2D", "SBS (side-by-side)", "OU/TB (over-under)"),
            )
        }))

        root.addView(label("SMB network share").apply { textSize = 19f })
        host = edit("Server IP or hostname")
        share = edit("Share name")
        username = edit("Username (blank for guest)")
        password = edit("Password").apply { inputType = 0x00000081 }
        domain = edit("Domain (usually blank)")
        smbPath = edit("Folder path (blank for root)")
        root.addView(row(host, share))
        root.addView(row(username, password))
        root.addView(row(domain, smbPath))
        root.addView(row(
            button("Browse SMB") { browseSmb(smbPath.text.toString()) },
            button("Up") {
                val current = smbPath.text.toString().trimEnd('\\')
                browseSmb(current.substringBeforeLast('\\', ""))
            },
        ))

        list = ListView(this).apply {
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries[position]
                if (entry.isDirectory) {
                    browseSmb(entry.path)
                } else {
                    smbPath.setText(entry.path.substringBeforeLast('\\', ""))
                    source.setText("smb://${host.text}/${entry.path.replace('\\', '/')}")
                    selectedSmbCredentials = currentCredentials()
                    status.text = "Selected ${entry.name}"
                    launchPlayer()
                }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        status = label("Choose a local file, enter an HTTP URL, or browse SMB.")
        root.addView(status)
        val scroll = ScrollView(this).apply { addView(root) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(button("PLAY SELECTED VIDEO IN VR") { launchPlayer() }, matchWrap().apply {
                height = dp(64)
                setMargins(dp(24), dp(8), dp(24), dp(16))
            })
        }
    }

    private fun browseSmb(path: String) {
        val credentials = currentCredentials()
        if (credentials.host.isBlank() || credentials.share.isBlank()) {
            status.text = "Enter an SMB server and share name."
            return
        }
        status.text = "Loading SMB…"
        io.execute {
            runCatching { SmbRepository(credentials).use { it.list(path) } }
                .onSuccess { result ->
                    runOnUiThread {
                        entries = result
                        smbPath.setText(path)
                        selectedSmbCredentials = credentials
                        list.adapter = object : ArrayAdapter<SmbEntry>(
                            this,
                            android.R.layout.simple_list_item_2,
                            android.R.id.text1,
                            result,
                        ) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val view = super.getView(position, convertView, parent)
                                val entry = getItem(position)!!
                                view.findViewById<TextView>(android.R.id.text1).apply {
                                    text = (if (entry.isDirectory) "📁 " else "▶ ") + entry.name
                                    setTextColor(Color.WHITE)
                                    isSingleLine = false
                                }
                                view.findViewById<TextView>(android.R.id.text2).apply {
                                    text = if (entry.isDirectory) "Folder" else formatBytes(entry.size)
                                    setTextColor(Color.rgb(150, 170, 185))
                                }
                                return view
                            }
                        }
                        status.text = "${result.size} items — folders first, then alphabetical"
                    }
                }
                .onFailure { error -> runOnUiThread { status.text = "SMB error: ${error.message}" } }
        }
    }

    private fun launchPlayer() {
        val uri = source.text.toString().trim()
        if (uri.isBlank()) {
            status.text = "Select a video first."
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            action = "dev.dreamplayer.action.PLAY_IN_VR"
            addCategory("com.yvr.intent.category.VR")
        }
        PlaybackSpec(
            uri = uri,
            title = uri.substringAfterLast('/'),
            stereoMode = StereoMode.fromWire(mode.selectedItemPosition),
            subtitleUri = subtitleUri?.toString(),
            smb = if (uri.startsWith("smb://")) selectedSmbCredentials ?: currentCredentials() else null,
            projectionExplicit = true,
        ).writeTo(intent)
        startActivity(intent)
    }

    private fun currentCredentials() = SmbCredentials(
        host = host.text.toString().trim(),
        share = share.text.toString().trim(),
        username = username.text.toString(),
        password = password.text.toString(),
        domain = domain.text.toString(),
    )

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.rgb(220, 229, 236))
        textSize = 15f
        setPadding(dp(5), dp(7), dp(5), dp(7))
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.rgb(110, 128, 142))
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(25, 32, 42))
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
            topMargin = dp(5)
            bottomMargin = dp(5)
        }) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "$bytes bytes"
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
