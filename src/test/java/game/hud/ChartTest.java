package game.hud;

import game.world.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChartTest {

    @Test
    void organsAndIssues_coverIllnesses() {
        String[] organs = GameState.organs();
        String[] issues = GameState.issues();
        assertEquals(9, organs.length);
        assertEquals(18, issues.length);
        assertTrue(java.util.Arrays.asList(organs).contains("General"));
        for (GameState.Illness ill : GameState.ILLNESSES) {
            assertTrue(java.util.Arrays.asList(organs).contains(ill.organ));
            assertTrue(java.util.Arrays.asList(issues).contains(ill.symptom));
        }
    }

    @Test
    void diagnosis_matchesOrganAndSymptom() {
        GameState.Illness ill = GameState.ILLNESSES[0];
        assertTrue(GameState.diagnosisCorrect(ill, ill.organ, ill.symptom));
        assertFalse(GameState.diagnosisCorrect(ill, ill.organ, "nope"));
        assertFalse(GameState.diagnosisCorrect(ill, "nope", ill.symptom));
        assertFalse(GameState.diagnosisCorrect(ill, null, ill.symptom));
        assertFalse(GameState.diagnosisCorrect(null, "x", "y"));
    }

    @Test
    void chartButtons_hitTest() {        int vw = 1280, vh = 720;
        int organCount = GameState.organs().length;
        int issueCount = GameState.issues().length;
        Hud.ChartLayout L = Hud.chartLayout(vw, vh, organCount, issueCount, 2);
        assertTrue(L.panel[2] > 500 && L.panel[3] > 400, "chart card must be big and readable");
        for (int i = 0; i < organCount; i++) {
            float[] r = L.organs[i];
            assertTrue(r[3] >= 30f, "organ buttons must be finger-sized");
            int code = Hud.pickChartButton(r[0] + r[2] * 0.5f, r[1] + r[3] * 0.5f,
                    vw, vh, organCount, issueCount, 2);
            assertEquals(Hud.CHART_ORGAN + i, code);
        }
        for (int i = 0; i < issueCount; i++) {
            float[] r = L.issues[i];
            int code = Hud.pickChartButton(r[0] + r[2] * 0.5f, r[1] + r[3] * 0.5f,
                    vw, vh, organCount, issueCount, 2);
            assertEquals(Hud.CHART_ISSUE + i, code);
        }
        assertEquals(Hud.CHART_DIAGNOSE, Hud.pickChartButton(
                L.diagnose[0] + 5, L.diagnose[1] + 5, vw, vh, organCount, issueCount, 2));
        assertEquals(Hud.CHART_DISCHARGE, Hud.pickChartButton(
                L.discharge[0] + 5, L.discharge[1] + 5, vw, vh, organCount, issueCount, 2));
        assertEquals(Hud.CHART_NONE, Hud.pickChartButton(10, vh - 10, vw, vh, organCount, issueCount, 2));
    }

    @Test
    void chartFits720p_worstCase() {

        int vw = 1280, vh = 720;
        Hud.ChartLayout L = Hud.chartLayout(vw, vh, 9, 18, 4);
        assertTrue(L.panel[1] + L.panel[3] <= vh - 4,
                "chart bottom must be onscreen, got " + (L.panel[1] + L.panel[3]));
        float[][] key = {L.diagnose, L.discharge};
        for (float[] r : key) {
            assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= vw && r[1] + r[3] <= vh,
                    "button must be onscreen: " + java.util.Arrays.toString(r));
        }
    }
}
