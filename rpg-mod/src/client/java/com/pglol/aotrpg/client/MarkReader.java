package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Discipline;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a drawn mark. The strokes are measured the way a person would look at them: how wide and
 * sweeping it is, how many sharp corners it has, whether its shapes close, how much of it is
 * straight, how round it turns, how mirror-like it is, whether there are dots. Each strength
 * favours a kind of drawing: wide symmetric sweeps are wings, jagged corners are a fang, closed
 * squared shapes a bulwark, clean lines and points an eye, round closed loops an ember.
 */
public final class MarkReader {
    private MarkReader() {}

    public record Reading(Discipline mark, int resonance) { }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public static Reading read(List<List<float[]>> strokes) {
        // Bounds (and the scale that makes the longest side 1).
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        for (var st : strokes) for (float[] p : st) {
            minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]);
        }
        double bw = Math.max(1e-3, maxX - minX), bh = Math.max(1e-3, maxY - minY), side = Math.max(bw, bh);
        double aspect = bw / bh;
        // Resample every stroke evenly, in the unit box.
        List<List<double[]>> rs = new ArrayList<>();
        double total = 0;
        int dots = 0;
        for (var st : strokes) {
            List<double[]> out = new ArrayList<>();
            double len = 0;
            double[] prev = null;
            for (float[] p : st) {
                double[] q = {(p[0] - minX) / side, (p[1] - minY) / side};
                if (prev == null) {
                    out.add(q);
                    prev = q;
                    continue;
                }
                double d = Math.hypot(q[0] - prev[0], q[1] - prev[1]);
                if (d < 0.02) continue;
                len += d;
                out.add(q);
                prev = q;
            }
            total += len;
            if (len < 0.08) dots++;
            rs.add(out);
        }
        total = Math.max(0.05, total);
        // Turning: sharp corners, gentle curve, straight runs.
        int sharp = 0, steps = 0, straightSteps = 0;
        double smoothTurn = 0;
        double closedSum = 0;
        int closedN = 0;
        for (var st : rs) {
            for (int i = 2; i < st.size(); i++) {
                double[] a = st.get(i - 2), b = st.get(i - 1), c = st.get(i);
                double a1 = Math.atan2(b[1] - a[1], b[0] - a[0]), a2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
                double t = Math.abs(Math.atan2(Math.sin(a2 - a1), Math.cos(a2 - a1)));
                steps++;
                if (t > Math.toRadians(55)) sharp++;
                else smoothTurn += t;
                if (t < Math.toRadians(8)) straightSteps++;
            }
            double len = 0;
            for (int i = 1; i < st.size(); i++) len += Math.hypot(st.get(i)[0] - st.get(i - 1)[0], st.get(i)[1] - st.get(i - 1)[1]);
            if (len > 0.3 && st.size() > 2) {
                double gap = Math.hypot(st.get(0)[0] - st.get(st.size() - 1)[0], st.get(0)[1] - st.get(st.size() - 1)[1]);
                closedSum += clamp(1 - gap / (len * 0.25));
                closedN++;
            }
        }
        double closed = closedN == 0 ? 0 : closedSum / closedN;
        double curvy = clamp(smoothTurn / total / 2.5);
        double sharpN = clamp(sharp / total / 2.0);
        double straight = steps == 0 ? 1 : straightSteps / (double) steps;
        // Mirror symmetry about the drawing's own middle.
        List<double[]> all = new ArrayList<>();
        for (var st : rs) all.addAll(st);
        double symErr = 0;
        int sn = 0;
        double mid = bw / side / 2;
        for (int i = 0; i < all.size(); i += Math.max(1, all.size() / 60)) {
            double[] p = all.get(i);
            double mx = 2 * mid - p[0], best = 9;
            for (double[] q : all) best = Math.min(best, Math.hypot(q[0] - mx, q[1] - p[1]));
            symErr += best;
            sn++;
        }
        double sym = sn == 0 ? 0 : clamp(1 - symErr / sn / 0.12);
        int n = strokes.size();

        double[] score = new double[Discipline.values().length];
        score[Discipline.SCOUT.ordinal()] = 1.2 * clamp((aspect - 1) / 1.4) + 0.8 * sym + 0.6 * curvy - 0.6 * closed;
        score[Discipline.VANGUARD.ordinal()] = 1.5 * sharpN + 0.4 * (1 - closed) + 0.3 * clamp((1 / aspect - 0.8) / 1.2) - 0.3 * sym;
        score[Discipline.GUARDIAN.ordinal()] = 1.0 * closed + 0.7 * straight + 0.6 * sharpN * closed + 0.4 * (1 - clamp(Math.abs(aspect - 1)));
        score[Discipline.MARKSMAN.ordinal()] = 1.1 * straight * (1 - closed) + 0.4 * clamp((n - 1) / 3.0) + 0.7 * clamp(dots) - 0.5 * curvy;
        score[Discipline.MEDIC.ordinal()] = 1.3 * closed * curvy + 0.5 * sym * closed + 0.4 * (1 - sharpN) - 0.3 * straight;
        int best = 0;
        double sum = 0;
        for (int i = 0; i < score.length; i++) {
            score[i] = Math.max(0.01, score[i]);
            sum += score[i];
            if (score[i] > score[best]) best = i;
        }
        // How strongly it reads as that one mark (shown as resonance).
        int resonance = (int) Math.round(55 + 44 * clamp((score[best] / sum - 0.2) / 0.45));
        return new Reading(Discipline.values()[best], resonance);
    }
}
