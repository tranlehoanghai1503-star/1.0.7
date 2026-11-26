package com.hai.selldonutsmp.util;

import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.Optional;

/** Doc phan chu nam ngay sau dau $ (hoac ky hieu mau xanh la dung thay dau $) trong mot dong chu. */
public final class ChatPrice {
    private ChatPrice() {}

    /**
     * Tra ve phan chu nam sau dau $ dau tien; null neu khong co.
     * requireGreen = true: dau $ phai co mau xanh la, hoac mot ky hieu xanh la (khong phai chu/so) dung thay dau $.
     */
    public static String after(Text message, boolean requireGreen) {
        StringBuilder after = new StringBuilder();
        boolean[] found = {false};

        message.<Object>visit((style, str) -> {
            if (found[0]) {
                after.append(str);
            } else {
                int i = str.indexOf('$');
                if (i >= 0 && (!requireGreen || isGreen(style))) {
                    found[0] = true;
                    after.append(str.substring(i + 1));
                } else if (requireGreen && isGreen(style) && isSymbolOnly(str)) {
                    found[0] = true;
                }
            }
            return Optional.empty();
        }, Style.EMPTY);

        return found[0] ? after.toString() : null;
    }

    public static boolean isGreen(Style style) {
        if (style == null || style.getColor() == null) return false;
        int rgb = style.getColor().getRgb();
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return g >= 150 && g > r + 40 && g > b + 40;
    }

    private static boolean isSymbolOnly(String str) {
        return !str.isBlank() && str.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    /** Mo ta tung doan chu + mau (ky tu la hien thanh ma unicode) de gui cho tac gia khi doc gia sai. */
    public static String describe(Text message) {
        StringBuilder sb = new StringBuilder();
        message.<Object>visit((style, str) -> {
            String color = style.getColor() == null ? "none" : String.format("#%06X", style.getColor().getRgb());
            sb.append('[').append(color).append(']');
            for (char c : str.toCharArray()) {
                if (c < 128) sb.append(c);
                else sb.append(String.format("\\u%04X", (int) c));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }
}
