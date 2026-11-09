package nl.sebastiaan.baan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All apps: a real (accessible) grid with a live search field. */
public class Drawer extends FrameLayout implements Apps.Listener {
    final MainActivity act;
    final EditText search;
    final GridView grid;
    final TextView count;
    final Adapter adapter = new Adapter();
    List<Apps.Item> shown = new ArrayList<>();
    TextView sortBtn;
    int sortMode;   // 0 = A-Z, 1 = nieuwste eerst
    float u;
    final Paint gp = new Paint();

    public Drawer(Context c) {
        super(c);
        act = (MainActivity) c;
        Theme.load(c);
        sortMode = App.prefs(c).getInt("drawerSort", 0);
        setWillNotDraw(false);
        setClickable(true);   // never let touches fall through to the home screen
        u = getResources().getDisplayMetrics().widthPixels / 412f;
        float ts = Theme.size(c);
        int acc = Theme.accent(c);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(18), px(14), px(18), px(6));
        TextView title = label("ALLE APPS", 11 * ts, acc);
        title.setLetterSpacing(0.3f);
        head.addView(title);
        count = label("", 11 * ts, Theme.alpha(acc, 150));
        count.setPadding(px(10), 0, 0, 0);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        sortBtn = label("", 11 * ts, acc);
        sortBtn.setLetterSpacing(0.2f);
        sortBtn.setPadding(px(10), px(10), px(10), px(10));
        updateSortLabel();
        sortBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
            sortMode = (sortMode + 1) % 2;
            App.prefs(getContext()).edit().putInt("drawerSort", sortMode).apply();
            updateSortLabel();
            filter();
        } });
        head.addView(sortBtn);
        TextView close = label("SLUIT  ×", 11 * ts, Theme.alpha(Theme.TEXT, 190));
        close.setLetterSpacing(0.2f);
        close.setPadding(px(12), px(10), 0, px(10));
        close.setContentDescription("App-lijst sluiten");
        close.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { act.closeDrawer(); } });
        head.addView(close);
        col.addView(head);

        search = new EditText(c);
        search.setHint("zoek app…");
        search.setHintTextColor(Theme.alpha(Theme.TEXT, 110));
        search.setTextColor(Theme.TEXT);
        search.setTypeface(Theme.mono);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * ts);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(px(46), px(12), px(14), px(12));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x99000000);
        sb.setStroke(Math.max(1, px(1)), Theme.alpha(acc, 200));
        search.setBackground(sb);
        search.setContentDescription("Zoek een app");
        FrameLayout sw = new FrameLayout(c);
        sw.addView(search, new LayoutParams(-1, -2));
        TextView prompt = label(">_", 15 * ts, acc);
        prompt.setTypeface(Theme.monoBold);
        prompt.setPadding(px(14), 0, 0, 0);
        sw.addView(prompt, new LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(px(18), px(4), px(18), px(10));
        col.addView(sw, slp);

        grid = new GridView(c);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(px(14));
        grid.setPadding(px(10), px(6), px(10), px(24));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { filter(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (!shown.isEmpty()) launch(shown.get(0));
                return true;
            }
        });
        filter();
    }

    int px(float dp) { return Math.round(dp * u); }

    TextView label(String s, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Theme.mono);
        return t;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Apps.listeners.add(this); }
    @Override protected void onDetachedFromWindow() { Apps.listeners.remove(this); super.onDetachedFromWindow(); }
    @Override public void onApps() { filter(); }

    void updateSortLabel() {
        if (sortBtn != null) sortBtn.setText(sortMode == 1 ? "NIEUW \u21C5" : "A\u2013Z \u21C5");
        if (sortBtn != null) sortBtn.setContentDescription(sortMode == 1 ? "Sortering: nieuwste eerst. Tik voor A-Z." : "Sortering: A tot Z. Tik voor nieuwste eerst.");
    }

    void filter() {
        String q = Apps.norm(search.getText().toString().trim());
        ArrayList<Apps.Item> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Apps.Item it : Apps.all) {
            if (q.isEmpty() || it.search.startsWith(q)) starts.add(it);
            else if (it.search.contains(" " + q) || it.search.contains(q) || initials(it.search).startsWith(q)) contains.add(it);
        }
        if (q.isEmpty()) {
            // browsing: apply the chosen sort over all apps
            if (sortMode == 1) Collections.sort(starts, new Comparator<Apps.Item>() {
                @Override public int compare(Apps.Item a, Apps.Item b) {
                    if (a.installed != b.installed) return Long.compare(b.installed, a.installed); // newest first
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            else Collections.sort(starts, new Comparator<Apps.Item>() {
                @Override public int compare(Apps.Item a, Apps.Item b) { return a.label.compareToIgnoreCase(b.label); }
            });
            shown = starts;
        } else {
            // searching: keep relevance order (starts-with first)
            starts.addAll(contains);
            shown = starts;
        }
        count.setText(q.isEmpty() ? String.valueOf(shown.size()) : shown.size() + " gevonden");
        adapter.notifyDataSetChanged();
    }

    static String initials(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split("[\\s\\-_.]+")) if (!w.isEmpty()) b.append(w.charAt(0));
        return b.toString();
    }

    void launch(Apps.Item it) {
        if (Apps.launch(getContext(), it)) act.closeDrawer();
        else act.toast("Kan app niet openen");
    }

    void reset(boolean keyboard) {
        search.setText("");
        grid.setSelection(0);
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard) {
            search.requestFocus();
            im.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        } else {
            search.clearFocus();
            im.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        im.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(0xF2020607);
        gp.setColor(Theme.alpha(Theme.accent(getContext()), 10));
        float step = 24 * u;
        for (float x = 0; x < getWidth(); x += step) cv.drawLine(x, 0, x, getHeight(), gp);
        for (float y = 0; y < getHeight(); y += step) cv.drawLine(0, y, getWidth(), y, gp);
    }

    // ---------------------------------------------------------------- grid cells
    class Cell extends View {
        Apps.Item item;
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        Cell(Context c) {
            super(c);
            setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (item != null) launch(item); } });
            setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (item != null) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); act.appOptions(item); }
                    return true;
                }
            });
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width * 1.12f * Math.min(1.15f, Theme.size(getContext()))));
        }

        @Override protected void onDraw(Canvas cv) {
            if (item == null) return;
            Context c = getContext();
            float w = getWidth(), hx = w * 0.62f;
            boolean color = true;   // app-lade toont altijd de originele kleuren
            Hex.draw(cv, item, w / 2, hx * 0.58f, hx, color, Theme.accent(c), Theme.deep(c), isPressed() ? 1 : 0, 255);
            Hex.badge(cv, w / 2 + hx * 0.36f, hx * 0.22f, 7.5f * u, NotifService.count(item.pkg), Theme.second(c), tp);
            tp.setTypeface(Theme.mono);
            float ts = 10.5f * u * Theme.size(c);
            tp.setTextSize(ts);
            tp.setColor(Theme.alpha(Theme.TEXT, 210));
            tp.setTextAlign(Paint.Align.CENTER);
            String s = item.label;
            while (s.length() > 1 && tp.measureText(s) > w * 0.96f) s = s.substring(0, s.length() - 1);
            if (!s.equals(item.label)) s = s.substring(0, Math.max(1, s.length() - 1)) + "…";
            cv.drawText(s, w / 2, hx * 1.12f + ts, tp);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            Cell c = v instanceof Cell ? (Cell) v : new Cell(getContext());
            c.item = shown.get(i);
            c.setContentDescription(c.item.label + (NotifService.count(c.item.pkg) > 0 ? ", " + NotifService.count(c.item.pkg) + " meldingen" : ""));
            c.invalidate();
            return c;
        }
    }

    static String up(String s) { return s.toUpperCase(Locale.ROOT); }
}
