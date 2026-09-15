package game.world;

import java.util.ArrayList;
import java.util.List;

public final class GameState {

    public double money = 0;
    public int day = 1;
    public int goalDone, goalTarget = 4;
    public double dayTime;
    public boolean dayActive = true;

    public final boolean[] orBusy = {false, false, false};
    public final double[] orReadyAt = {0, 0, 0};
    public int orRequested = -1;
    public double orWaitTotal;

    public String prompt = "";

    public String banner = "";
    public double bannerUntil;

    public enum Screen { MAIN_MENU, PLAYING, PAUSED, DAY_END }
    public Screen screen = Screen.MAIN_MENU;
    public int menuIndex;

    public int toolSelected;

    public static final class Illness {
        public final String name, organ, symptom;
        public final boolean or;
        public final String[] steps;
        public Illness(String name, String organ, String symptom, boolean or, String[] steps) {
            this.name = name; this.organ = organ; this.symptom = symptom; this.or = or;
            this.steps = steps;
        }
    }

    public static final Illness[] ILLNESSES = {

            new Illness("Collapsed Lung", "Lung", "Collapse", true,
                    new String[]{"OR prep", "Bike pump re-inflate", "Tape the chest"}),
            new Illness("Lung Fluids", "Lung", "Fluids", true,
                    new String[]{"OR prep", "Tape the chest", "Ice pack the chest"}),
            new Illness("Open Chest", "Chest", "Open", false,
                    new String[]{"Tape the chest", "Ice pack the chest"}),
            new Illness("Chest Rattle", "Chest", "Rattle", false,
                    new String[]{"Bike pump steady", "Placebo dose"}),
            new Illness("Squeaky Heart Valve", "Heart", "Squeak", true,
                    new String[]{"OR prep", "Bike pump prime", "Placebo dose"}),
            new Illness("Heart Palpitations", "Heart", "Palpitations", false,
                    new String[]{"Placebo dose", "Ice pack calm"}),
            new Illness("Low Blood Pressure", "Blood", "Low Pressure", false,
                    new String[]{"Bike pump pressure"}),
            new Illness("Blood Loss", "Blood", "Blood Loss", false,
                    new String[]{"Coloured water transfusion"}),
            new Illness("Bleeding Out", "Skin", "Bleeding", false,
                    new String[]{"Tape the wound", "Ice the wound", "Coloured water top-up"}),
            new Illness("Small Leak", "Skin", "Leak", false,
                    new String[]{"Tape the leak"}),
            new Illness("Large Leak", "Skin", "Large Leak", false,
                    new String[]{"Super glue the leak"}),
            new Illness("Infected Scratch", "Skin", "Infection", false,
                    new String[]{"Placebo dose"}),
            new Illness("Broken Leg", "Leg", "Broken", false,
                    new String[]{"Tape the leg"}),
            new Illness("Shattered Leg", "Leg", "Shattered", false,
                    new String[]{"Super glue the leg", "Ice the leg"}),
            new Illness("Compound Fracture", "Leg", "Compound", true,
                    new String[]{"OR prep", "Super glue the bone", "Tape the leg", "Ice the leg"}),
            new Illness("Head Swelling", "Head", "Swelling", false,
                    new String[]{"Ice the head"}),
            new Illness("Concussion", "Head", "Concussion", false,
                    new String[]{"Ice the head", "Placebo dose"}),
            new Illness("Stomach Leak", "Stomach", "Stomach Leak", true,
                    new String[]{"OR prep", "Super glue the stomach", "Ice the belly"}),
    };

    public boolean requestOr() {
        if (orRequested >= 0) {
            return true;
        }
        for (int i = 0; i < orBusy.length; i++) {
            if (!orBusy[i]) {
                orBusy[i] = true;
                orRequested = i;
                double base = 10.0 + Math.random() * 20.0;
                orWaitTotal = base;
                orReadyAt[i] = dayTime + base;
                return true;
            }
        }
        return false;
    }

    public void tick(double dt) {
        if (screen != Screen.PLAYING) {
            return;
        }
        dayTime += dt;
        if (orRequested >= 0 && dayTime >= orReadyAt[orRequested]) {
            banner("OR" + (orRequested + 1) + " READY - bring the patient up!");
            Npc withOr = patientWithOrRequested();
            if (withOr != null) {
                withOr.orReady = true;
            }

            orBusy[orRequested] = false;
            orRequested = -1;
        }
        if (banner != null && dayTime >= bannerUntil) {
            banner = "";
        }
    }

    private Npc patientWithOrRequested() {
        for (Npc p : patients) {
            if (p.orRequested && !p.orReady) {
                return p;
            }
        }
        return null;
    }

    public void banner(String msg) {
        banner = msg;
        bannerUntil = dayTime + 4.0;
    }

    public final List<Npc> patients = new ArrayList<>();
    public final List<Npc> staff = new ArrayList<>();
    private int patientSeq;

    public Npc spawnPatient() {
        int look = patientSeq;
        float[] gown = Npc.GOWNS[Math.floorMod(look, Npc.GOWNS.length)];
        Npc n = new Npc(Npc.Role.PATIENT, -8.4f, 0.4f, 168f,
                gown[0], gown[1], gown[2], look);
        n.name = "P" + (++patientSeq);
        Illness ill = ILLNESSES[(int) (Math.random() * ILLNESSES.length)];
        n.illness = ill.name;
        n.symptom = ill.symptom;
        float[] rgb = symptomColor(ill);
        n.setSymptomMarker(visualFor(ill), rgb[0], rgb[1], rgb[2]);
        n.orRequired = ill.or;
        n.state = Npc.PatientState.WAITING_LOBBY;
        n.setPath(-8.4f, 158f, -8.4f, 120f);
        patients.add(n);
        return n;
    }

    public int dischargePay(Npc p) {

        Illness ill = findIllness(p.illness);
        int need = ill != null ? ill.steps.length : 2;
        int done = Math.min(Math.max(0, p.treatStepsDone), need);
        int pay = 10 + done * 15;
        if (ill != null && ill.or && done >= need
                && p.state == Npc.PatientState.TREATED
                && !p.orReady && p.surgeryTimer < 0f) {
            pay += 10;
        }
        money += pay;
        goalDone++;
        return pay;
    }

    public Illness findIllness(String name) {
        for (Illness i : ILLNESSES) {
            if (i.name.equals(name)) {
                return i;
            }
        }
        return null;
    }

    public static String[] organs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Illness i : ILLNESSES) {
            if (!out.contains(i.organ)) {
                out.add(i.organ);
            }
        }

        if (!out.contains("General")) {
            out.add("General");
        }
        return out.toArray(new String[0]);
    }

    public static String[] issues() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Illness i : ILLNESSES) {
            if (!out.contains(i.symptom)) {
                out.add(i.symptom);
            }
        }
        return out.toArray(new String[0]);
    }

    public static boolean diagnosisCorrect(Illness ill, String organ, String issue) {
        return ill != null && organ != null && issue != null
                && ill.organ.equals(organ) && ill.symptom.equals(issue);
    }

    public static String visualFor(Illness ill) {
        if (ill == null) {
            return "chest";
        }
        return switch (ill.organ) {
            case "Lung", "Chest", "Heart" -> "chest";
            case "Head" -> "head";
            case "Leg" -> "leg";
            case "Skin" -> "arm";
            case "Blood" -> "pale";
            case "Stomach", "General" -> "belly";
            default -> "chest";
        };
    }

    public static float[] symptomColor(Illness ill) {
        if (ill == null) {
            return new float[]{1f, 0.2f, 0.1f};
        }
        return switch (ill.name) {
            case "Collapsed Lung" -> new float[]{0.20f, 0.50f, 1.00f};
            case "Lung Fluids" -> new float[]{0.30f, 0.80f, 1.00f};
            case "Open Chest" -> new float[]{1.00f, 0.55f, 0.10f};
            case "Chest Rattle" -> new float[]{0.70f, 0.30f, 1.00f};
            case "Squeaky Heart Valve" -> new float[]{1.00f, 0.40f, 0.70f};
            case "Heart Palpitations" -> new float[]{0.45f, 1.00f, 0.20f};
            case "Low Blood Pressure" -> new float[]{0.60f, 0.30f, 0.90f};
            case "Blood Loss" -> new float[]{0.35f, 0.70f, 1.00f};
            case "Bleeding Out" -> new float[]{1.00f, 0.15f, 0.15f};
            case "Small Leak" -> new float[]{0.20f, 1.00f, 1.00f};
            case "Large Leak" -> new float[]{0.10f, 0.80f, 0.70f};
            case "Infected Scratch" -> new float[]{0.20f, 1.00f, 0.30f};
            case "Broken Leg" -> new float[]{1.00f, 0.55f, 0.05f};
            case "Shattered Leg" -> new float[]{1.00f, 0.30f, 0.10f};
            case "Compound Fracture" -> new float[]{1.00f, 0.20f, 0.25f};
            case "Head Swelling" -> new float[]{1.00f, 0.80f, 0.10f};
            case "Concussion" -> new float[]{0.55f, 0.75f, 1.00f};
            case "Stomach Leak" -> new float[]{0.30f, 1.00f, 0.35f};
            default -> new float[]{1f, 0.2f, 0.1f};
        };
    }

    public static String symptomColorName(Illness ill) {
        if (ill == null) {
            return "RED";
        }
        return switch (ill.name) {
            case "Collapsed Lung" -> "BLUE";
            case "Lung Fluids" -> "CYAN-BLUE";
            case "Open Chest" -> "ORANGE";
            case "Chest Rattle" -> "PURPLE";
            case "Squeaky Heart Valve" -> "PINK";
            case "Heart Palpitations" -> "LIME";
            case "Low Blood Pressure" -> "PURPLE";
            case "Blood Loss" -> "ICE-BLUE";
            case "Bleeding Out" -> "RED";
            case "Small Leak" -> "CYAN";
            case "Large Leak" -> "TEAL";
            case "Infected Scratch" -> "GREEN";
            case "Broken Leg" -> "ORANGE";
            case "Shattered Leg" -> "ORANGE-RED";
            case "Compound Fracture" -> "RED-ORANGE";
            case "Head Swelling" -> "YELLOW";
            case "Concussion" -> "YELLOW-BLUE";
            case "Stomach Leak" -> "GREEN";
            default -> "RED";
        };
    }
}
