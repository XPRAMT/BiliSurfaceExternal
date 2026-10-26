package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** Text that remains legible on video through a black, zero-offset outline shadow. */
final class OutlinedTextView extends TextView {
    OutlinedTextView(Context context) {
        super(context);
    }

    OutlinedTextView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    void setOutlineWidthPx(float value) {
        float radius = Math.max(0.0f, value);
        // A zero-offset black shadow is rendered around every glyph. Unlike a Paint style swap
        // inside TextView.onDraw(), it cannot be replaced by TextView's ColorStateList.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setShadowLayer(radius, 0.0f, 0.0f, Color.BLACK);
    }
}
