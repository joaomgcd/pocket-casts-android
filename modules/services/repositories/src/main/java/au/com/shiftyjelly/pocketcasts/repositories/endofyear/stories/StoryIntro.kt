package au.com.shiftyjelly.pocketcasts.repositories.endofyear.stories

import androidx.compose.ui.graphics.Color
class StoryIntro : Story() {
    override val identifier: String = "intro"
    override val backgroundColor: Color = Color(0xFF1A1A1A)
    override val shareable: Boolean = false
}
