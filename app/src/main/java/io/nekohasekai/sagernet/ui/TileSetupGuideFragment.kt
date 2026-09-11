package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.widget.ListListener

/**
 * "Quick Tiles Setup" drawer screen: the one-time adb command both the Dev
 * and VLoad Tether QS tiles need (WRITE_SECURE_SETTINGS can't be granted
 * through a normal Android permission popup - see DevOptionsSettings), shown
 * with a tap-to-copy action so it's usable straight off a freshly-connected
 * new phone without retyping the package name by hand each time.
 */
class TileSetupGuideFragment : ToolbarFragment(R.layout.layout_tile_guide) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.tile_guide_title)

        parentFragmentManager.beginTransaction()
            .replace(R.id.tile_guide_fragment_holder, TileGuideContent())
            .commitAllowingStateLoss()
    }

    class TileGuideContent : MaterialAboutFragment() {

        // BuildConfig.APPLICATION_ID, not a hardcoded package name: this
        // screen needs to show the right command whichever build variant
        // (oss/fdroid/play, debug/release - each has its own applicationId
        // suffix) is actually installed on the phone it's opened on.
        private val grantCommand
            get() = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.tile_guide_dev_card_title)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_device_developer_mode)
                                .text(R.string.tile_guide_dev_what)
                                .setOnClickAction { }
                                .build()
                        )
                        .build()
                )
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.tile_guide_tether_card_title)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_hardware_router)
                                .text(R.string.tile_guide_tether_what)
                                .setOnClickAction { }
                                .build()
                        )
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_social_share)
                                .text(R.string.tile_guide_shizuku_title)
                                .subText(R.string.tile_guide_shizuku_body)
                                .setOnClickAction {
                                    requireContext().launchCustomTab("https://shizuku.rikka.app/")
                                }
                                .build()
                        )
                        .build()
                )
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.tile_guide_permission_title)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_settings_password)
                                .text(R.string.tile_guide_permission_body)
                                .setOnClickAction { }
                                .build()
                        )
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_action_settings)
                                .text(grantCommand)
                                .setOnClickAction { copyToClipboard(grantCommand) }
                                .build()
                        )
                        .build()
                )
                .build()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
        }

        private fun copyToClipboard(text: String) {
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb command", text))
            Toast.makeText(requireContext(), R.string.tile_guide_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
