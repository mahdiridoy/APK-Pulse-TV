package com.lagradost.cloudstream3.ui.watchtogether

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.showInputMethod
import com.lagradost.cloudstream3.utils.txt

/**
 * Shared Watch Together dialog used by both the main Settings screen and the UI settings
 * screen. Newcomers pick Host or Guest:
 *
 *  - Host:  room name + your name -> create room -> the room code is shown to share.
 *  - Guest: room code + your name -> join the host's room.
 *
 * Once connected it shows the live roster of everyone in the room.
 */
object WatchTogetherDialog {

    private fun resolveColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attr, value, true)) {
            return value.data
        }
        return Color.WHITE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun buildLabel(context: Context, text: String, size: Float = 16f): TextView =
        TextView(context).apply {
            setTextColor(resolveColor(context, R.attr.textColor))
            textSize = size
            this.text = text
        }

    private fun buildInput(context: Context, hint: String): EditText =
        EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.post { showInputMethod(view) }
                }
            }
            setOnClickListener {
                it.requestFocus()
                it.post { showInputMethod(it) }
            }
        }

    private fun buildButton(context: Context, text: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(context).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    fun show(context: Context) {
        val manager = WatchTogetherManager
        val ctx = context

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.watch_together_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        lateinit var showHostPage: () -> Unit
        lateinit var showGuestPage: () -> Unit
        lateinit var showStartPage: () -> Unit

        fun setPage(view: View) {
            root.removeAllViews()
            root.addView(view)
        }

        /** Requests focus on the first input of the current page and opens the keyboard. */
        fun focusFirstInput() {
            val window = dialog.window
            window?.apply {
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                // AlertController sets FLAG_ALT_FOCUSABLE_IM when it does not detect a text
                // editor (our dialog uses a LinearLayout wrapper, so it never does). That flag
                // tells the IME to stay hidden, which is why tapping the fields shows no keyboard.
                clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
            }
            // run on the dialog window once it has been laid out, then request focus and show
            // the input method on a second pass so the focus change has actually been applied
            dialog.window?.decorView?.post {
                val input = (0 until root.childCount)
                    .map { root.getChildAt(it) }
                    .filterIsInstance<EditText>()
                    .firstOrNull() ?: return@post
                input.requestFocus()
                input.post { showInputMethod(input) }
            }
        }

        fun showError(message: String) {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.watch_together_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        /* ======================================================
         * PEOPLE ROSTER (shown once connected)
         * ====================================================== */

        fun buildRosterPage(
            context: Context,
            manager: WatchTogetherManager,
            onUsersChanged: () -> Unit
        ): View {
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 4), 0, 0)
            }

            val isHost = manager.role == WatchTogetherManager.Role.HOST

            page.addView(
                buildLabel(
                    context,
                    if (isHost)
                        context.getString(R.string.watch_together_connected_host)
                    else
                        context.getString(R.string.watch_together_connected_viewer)
                )
            )

            val codeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 4), 0, dp(context, 8))
            }
            codeRow.addView(
                TextView(context).apply {
                    setTextColor(resolveColor(context, R.attr.textColor))
                    textSize = 20f
                    text = manager.roomCode ?: ""
                }
            )
            codeRow.addView(
                buildButton(context, context.getString(R.string.watch_together_copy_code)) {
                    manager.roomCode?.takeIf { it.isNotBlank() }?.let { code ->
                        clipboardHelper(txt(R.string.watch_together_room_code), code)
                    }
                }
            )
            page.addView(codeRow)

            // Sync status indicator (for viewers)
            val syncStatusLabel = TextView(context).apply {
                setTextColor(resolveColor(context, R.attr.textColor))
                textSize = 12f
                val delta = manager.syncDeltaMs
                text = when {
                    !isHost && delta == 0L -> context.getString(R.string.watch_together_sync_status, "Synced", "")
                    !isHost && delta > 0 -> context.getString(R.string.watch_together_sync_ahead, "", "${delta / 1000}s")
                    !isHost && delta < 0 -> context.getString(R.string.watch_together_sync_behind, "", "${kotlin.math.abs(delta) / 1000}s")
                    else -> ""
                }
                visibility = if (!isHost) View.VISIBLE else View.GONE
                setPadding(0, dp(context, 2), 0, dp(context, 6))
            }
            page.addView(syncStatusLabel)

            page.addView(buildLabel(context, context.getString(R.string.watch_together_people), 14f))

            val roster = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 4), 0, 0)
            }
            page.addView(roster)

            fun render() {
                roster.removeAllViews()
                val users = manager.users
                if (users.isEmpty()) {
                    roster.addView(
                        buildLabel(
                            context,
                            context.getString(R.string.watch_together_no_viewers),
                            14f
                        )
                    )
                }
                users.forEach { user ->
                    val isSelf = user.name == manager.displayName
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(context, 2), 0, dp(context, 2))
                    }
                    val label = buildLabel(
                        context,
                        user.name + if (isSelf) " (${context.getString(R.string.watch_together_you)})" else "",
                        14f
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(label)
                    roster.addView(row)
                }
            }
            render()

            // ---- Chat section ----
            page.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(context, 10), 0, dp(context, 4))
                    addView(buildLabel(context, context.getString(R.string.watch_together_chat), 14f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }
            )

            // Chat message list (scrollable, max height ~150dp)
            val chatScrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 150)
                )
                isVerticalScrollBarEnabled = true
            }
            val chatContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(context, 4))
            }
            chatScrollView.addView(chatContainer)
            page.addView(chatScrollView)

            fun renderChat() {
                chatContainer.removeAllViews()
                val messages = manager.chatMessages.takeLast(30)
                if (messages.isEmpty()) {
                    chatContainer.addView(
                        buildLabel(context, "No messages yet", 12f)
                    )
                }
                messages.forEach { msg ->
                    val bubble = TextView(context).apply {
                        textSize = 13f
                        setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
                        setTextColor(if (msg.isOwn) Color.WHITE else resolveColor(context, R.attr.textColor))
                        text = if (msg.isOwn) msg.message else "${msg.sender}: ${msg.message}"
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 2)
                            topMargin = dp(context, 2)
                        }
                    }
                    chatContainer.addView(bubble)
                }
                // Auto-scroll to bottom
                chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            }
            renderChat()

            // Chat input row
            val chatInputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 4), 0, dp(context, 4))
            }
            val chatInput = EditText(context).apply {
                hint = context.getString(R.string.watch_together_chat_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            chatInputRow.addView(chatInput)
            chatInputRow.addView(
                buildButton(context, context.getString(R.string.watch_together_chat_send)) {
                    val text = chatInput.text.toString().trim()
                    if (text.isEmpty()) return@buildButton
                    manager.sendChat(text)
                    chatInput.text.clear()
                }
            )
            page.addView(chatInputRow)

            page.addView(
                buildButton(context, context.getString(R.string.watch_together_disconnect)) {
                    manager.disconnect()
                    dialog.dismiss()
                }
            )

            // Set up live chat listener
            val chatListener: (WatchTogetherManager.ChatMessage) -> Unit = { _ ->
                if (manager.connected) renderChat()
            }
            manager.chatListener = chatListener

            // Also listen for sync status updates
            val syncListener: (Long, Boolean) -> Unit = { delta, _ ->
                if (!isHost) {
                    val label = when {
                        delta == 0L -> context.getString(R.string.watch_together_sync_status, "Synced", "")
                        delta > 0 -> context.getString(R.string.watch_together_sync_ahead, "", "${delta / 1000}s")
                        else -> context.getString(R.string.watch_together_sync_behind, "", "${kotlin.math.abs(delta) / 1000}s")
                    }
                    syncStatusLabel.text = label
                }
            }
            manager.syncStatusListener = syncListener

            return page
        }

        fun refreshRoster(users: List<WatchTogetherManager.RoomUser>) {
            setPage(buildRosterPage(ctx, manager) { refreshRoster(manager.users) })
        }

        /* ======================================================
         * HOST PAGE
         * ====================================================== */

        fun buildHostPage(): View {
            val page = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }

            val roomName = buildInput(
                ctx,
                ctx.getString(R.string.watch_together_room_name)
            ).apply {
                setText(manager.lastRoomName ?: "")
            }
            val yourName = buildInput(
                ctx,
                ctx.getString(R.string.watch_together_your_name)
            ).apply {
                setText(manager.displayName.ifBlank { "Host" })
            }

            page.addView(roomName)
            page.addView(yourName)

            page.addView(
                buildButton(ctx, ctx.getString(R.string.watch_together_create_room)) {
                    val name = roomName.text.toString().trim()
                    if (name.isEmpty()) return@buildButton
                    manager.displayName = yourName.text.toString().trim().ifBlank { "Host" }
                    manager.lastRoomName = name
                    manager.createRoom(name) { result ->
                        result.onSuccess { info ->
                            manager.connectAsHost(info.roomCode, info.hostToken)
                            setPage(buildRosterPage(ctx, manager) { refreshRoster(manager.users) })
                        }.onFailure { error ->
                            showError(error.message ?: "Could not create room.")
                        }
                    }
                }
            )
            page.addView(
                buildButton(ctx, ctx.getString(R.string.watch_together_go_back)) {
                    showStartPage()
                }
            )

            return page
        }

        /* ======================================================
         * GUEST PAGE
         * ====================================================== */

        fun buildGuestPage(): View {
            val page = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }

            val roomCode = buildInput(
                ctx,
                ctx.getString(R.string.watch_together_room_code_hint)
            )
            val yourName = buildInput(
                ctx,
                ctx.getString(R.string.watch_together_your_name)
            ).apply {
                setText(manager.displayName.ifBlank { "Guest" })
            }

            page.addView(roomCode)
            page.addView(yourName)

            page.addView(
                buildButton(ctx, ctx.getString(R.string.watch_together_join_room)) {
                    val code = roomCode.text.toString().trim()
                    if (code.isEmpty()) return@buildButton
                    manager.displayName = yourName.text.toString().trim().ifBlank { "Guest" }

                    // Check if the room exists on the server before connecting
                    manager.checkRoom(code) { exists, error ->
                        if (!exists) {
                            val msg = error ?: ctx.getString(R.string.watch_together_room_not_found)
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            return@checkRoom
                        }
                        manager.connectAsViewer(code)
                        setPage(buildRosterPage(ctx, manager) { refreshRoster(manager.users) })
                    }
                }
            )
            page.addView(
                buildButton(ctx, ctx.getString(R.string.watch_together_go_back)) {
                    showStartPage()
                }
            )

            return page
        }

        /* ======================================================
         * START PAGE (Host / Guest choice)
         * ====================================================== */

        fun buildStartPage(): View {
            val page = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }

            page.addView(buildLabel(ctx, ctx.getString(R.string.watch_together_how_would_you_like_to_join)))

            val hostButton = buildButton(ctx, ctx.getString(R.string.watch_together_host_option)) {
                showHostPage()
            }
            page.addView(hostButton)
            page.addView(buildLabel(ctx, ctx.getString(R.string.watch_together_host_option_desc), 12f))

            val guestButton = buildButton(ctx, ctx.getString(R.string.watch_together_guest_option)) {
                showGuestPage()
            }
            page.addView(guestButton)
            page.addView(buildLabel(ctx, ctx.getString(R.string.watch_together_guest_option_desc), 12f))

            return page
        }

        /* ======================================================
         * WIRE UP LIVE UPDATES + SHOW
         * ====================================================== */

        showHostPage = {
            setPage(buildHostPage())
            focusFirstInput()
        }
        showGuestPage = {
            setPage(buildGuestPage())
            focusFirstInput()
        }
        showStartPage = { setPage(buildStartPage()) }

        if (manager.connected) {
            setPage(buildRosterPage(ctx, manager) { refreshRoster(manager.users) })
        } else {
            showStartPage()
        }

        val rosterUpdater: (List<WatchTogetherManager.RoomUser>) -> Unit = { users ->
            if (manager.connected) {
                refreshRoster(users)
            } else if (dialog.isShowing) {
                // The host left and the room was closed; close the dialog so the app
                // can return everyone to the main menu.
                dialog.dismiss()
            }
        }

        manager.usersListener = rosterUpdater

        dialog.setOnDismissListener {
            if (manager.usersListener == rosterUpdater) {
                manager.usersListener = null
            }
            manager.chatListener = null
            manager.syncStatusListener = null
        }

        dialog.setOnShowListener {
            // AlertController applies FLAG_ALT_FOCUSABLE_IM when the custom view is not itself a
            // text editor, which hides the IME. Clear it so the keyboard can open on tap.
            dialog.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
        }

        dialog.show()
    }
}