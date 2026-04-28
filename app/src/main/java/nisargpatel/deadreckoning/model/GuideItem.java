package nisargpatel.deadreckoning.model;

/**
 * Represents a single page in the in-app guide / tutorial system.
 * Each item has a title, descriptive content, and an icon drawable resource.
 */
public class GuideItem {
    private String title;
    private String content;
    private int iconRes;

    /**
     * @param title   Short heading shown at the top of the guide page.
     * @param content Body text describing the feature or step.
     * @param iconRes Android drawable resource ID for the page icon.
     */
    public GuideItem(String title, String content, int iconRes) {
        this.title = title;
        this.content = content;
        this.iconRes = iconRes;
    }

    /** @return Short heading for this guide page. */
    public String getTitle() { return title; }

    /** @return Full descriptive body text for this guide page. */
    public String getContent() { return content; }

    /** @return Android drawable resource ID for the page icon. */
    public int getIconRes() { return iconRes; }
}
