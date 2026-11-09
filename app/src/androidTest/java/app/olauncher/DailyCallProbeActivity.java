package app.olauncher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A visible call window from an otherwise blocked app. */
public class DailyCallProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Synthetic call in progress");
        setContentView(text);
    }
}
