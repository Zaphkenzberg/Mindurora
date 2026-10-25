package mindustry.ui;

import arc.graphics.*;
import arc.math.*;
import arc.scene.actions.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/** Displays a list of items, e.g. launched items.*/
public class ItemsDisplay extends Table{
    boolean collapsed;

    // AURORA: numbers roll smoothly to new values instead of jumping (shared stock refreshes every ~2 s)
    // — Числа плавно докручуються до нових значень замість стрибків (спільний склад оновлюється кожні ~2 с)
    /** Time to reach a new value, ticks: growth rolls over ~5 s (production arrives every ~5 s, so it looks continuous),
     * spending drops fast — За скільки тіків доїхати до нового значення: ріст — за ~5 с (виробництво приходить кожні ~5 с,
     * тож виглядає безперервно), витрата — швидко. */
    static final float rollUpTicks = 300f, rollDownTicks = 30f;
    float[] shown, rate;

    public ItemsDisplay(){
        rebuild(new ItemSeq());
    }

    public void rebuild(ItemSeq items){
        rebuild(items, null);
    }

    public void rebuild(ItemSeq items, @Nullable boolean[] shine){
        clear();
        top().left();
        margin(0);

        if(items == null) return;

        if(shown == null || shown.length != content.items().size){
            shown = new float[content.items().size];
            rate = new float[content.items().size];
            java.util.Arrays.fill(shown, Float.NaN);
        }

        table(Tex.button, c -> {
            c.margin(10).marginLeft(12).marginTop(15f);
            c.marginRight(12f);
            c.left();

            Collapser col = new Collapser(base -> base.pane(t -> {
                t.marginRight(30f);
                t.left();
                for(Item item : content.items()){
                    if(!items.has(item)) continue;

                    int id = item.id, target = items.get(item);
                    if(Float.isNaN(shown[id])) shown[id] = target; // first look: no rolling — перший погляд: без докручування
                    rate[id] = Math.max(Math.abs(target - shown[id]) / (target >= shown[id] ? rollUpTicks : rollDownTicks), 0.05f);
                    Label label = t.add(UI.formatAmount((int)shown[id])).left().get();
                    label.update(() -> {
                        float before = shown[id];
                        shown[id] = Mathf.approach(shown[id], target, rate[id] * Time.delta);
                        if((int)before != (int)shown[id]) label.setText(UI.formatAmount((int)shown[id]));
                    });
                    t.image(item.uiIcon).size(8 * 3).padLeft(4).padRight(4);
                    t.add(item.localizedName).color(Color.lightGray).left();
                    t.row();

                    if(shine != null && shine[item.id]){
                        label.setColor(Pal.accent);
                        label.actions(Actions.color(Color.white, 0.75f, Interp.fade));
                    }
                }
            }).scrollX(false), false).setDuration(0.3f);

            col.setCollapsed(collapsed, false);

            c.button("@globalitems", Icon.downOpen, Styles.flatTogglet, col::toggle).update(t -> {
                t.setChecked(col.isCollapsed());
                collapsed = col.isCollapsed();
                ((Image)t.getChildren().get(1)).setDrawable(col.isCollapsed() ? Icon.upOpen : Icon.downOpen);
            }).padBottom(4).left().fillX().margin(12f).minWidth(200f);
            c.row();
            c.add(col);
        });
    }
}
