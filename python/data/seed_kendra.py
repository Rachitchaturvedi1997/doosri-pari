# =============================================================================
# Seed content for the Yog Kendra. Plan sections 7.1 and 20.
#
# THIS IS A DRAFT FOR REVIEW, NOT A CLINICAL PROGRAMME. Plan 20: "every yoga
# programme reviewed by a certified yoga therapist and a physiotherapist before
# release". The tool that loads this (python/tools/seed_content.py) therefore
# creates every programme INACTIVE and only `--activate-programmes` (to be run
# after that review) switches them on. Nothing here has been tested on a body.
#
# The safety flags are the ones in python/models/base.py. A pose lists the flags
# it is CONTRAINDICATED for; if it has a `modification`, a flag that can be worked
# around offers the modified form instead of removing the pose (features/kendra.py
# screen_programme). Wording is plain, unhurried, and never Sanskrit-first.
# =============================================================================

# slug, name, instruction, hold_seconds, reps, requires_standing, requires_floor,
# contraindications, modification, breathing_cue
POSES = [
    # --- seated ---------------------------------------------------------------
    ("seated-mountain", "Seated mountain",
     "Sit tall on a firm chair, feet flat on the floor, hands resting on your thighs. Let your shoulders drop. Look straight ahead.",
     30, None, False, False, [], None, "Breathe slowly in through the nose and out through the nose."),
    ("seated-cat-cow", "Seated cat and cow",
     "Hands on your knees. As you breathe in, gently lift your chest and look forward. As you breathe out, round your back and let your head drop softly.",
     None, 6, False, False, ["osteoporosis", "recent_surgery"],
     "Make the movement very small, or only lift the chest and skip the rounding.", "In as you lift, out as you round."),
    ("seated-side-stretch", "Seated side stretch",
     "Reach your right arm up and over to the left, only as far as feels comfortable. Keep both hips on the chair. Come back slowly and change sides.",
     12, 3, False, False, ["recent_surgery"], "Keep the lower arm holding the seat of the chair.", "Breathe normally. Do not hold your breath."),
    ("seated-twist", "Seated gentle twist",
     "Sit tall. Turn your upper body a little to the right, one hand on the back of the chair. Look over your shoulder if it feels good. Come back and change sides.",
     10, 3, False, False, ["osteoporosis", "hernia", "recent_surgery"], "Turn only your head and shoulders, not your waist.", "Breathe out as you turn."),
    ("seated-forward-bend", "Seated gentle forward bend",
     "Sit at the front of the chair, feet wide. Slide your hands down your thighs toward your knees. Stop when you feel a light stretch. Come up slowly.",
     8, 3, False, False, ["high_bp", "glaucoma", "osteoporosis", "hernia", "recent_surgery", "vertigo"],
     "Only lean forward a little and keep your head above your heart.", "Breathe out as you lean, in as you come up."),
    ("seated-eagle-arms", "Seated eagle arms",
     "Cross your right arm over your left in front of you, and hold your shoulders. Lift your elbows a little. Breathe. Change sides.",
     15, 2, False, False, [], "If crossing the arms is hard, hug yourself gently.", "Breathe slowly."),
    ("seated-butterfly", "Butterfly, feet on the floor",
     "Sit tall. Let your knees fall a little apart, the soles of your feet flat on the floor. Rest your hands on your knees and let your hips soften.",
     20, None, False, False, ["hip_replacement"], "Keep your knees no wider than your hips.", "Breathe into your belly."),
    ("seated-marching", "Seated marching",
     "Lift your right knee a little, put it down, lift the left. Move like a slow march, swinging your arms gently.",
     None, 20, False, False, [], None, "Breathe steadily."),
    ("seated-leg-extension", "Seated leg extension",
     "Straighten your right leg in front of you and hold for a moment. Lower it slowly. Change legs.",
     5, 8, False, False, ["knee_replacement"], "Straighten only as far as is comfortable, no locking the knee.", "Out as you lift, in as you lower."),
    ("ankle-circles", "Ankle circles",
     "Lift one foot a little. Draw slow circles with your toes, five one way, five the other. Change feet.",
     None, 10, False, False, [], None, "Breathe normally."),
    ("wrist-circles", "Wrist circles",
     "Hold your hands out in front. Turn your wrists in slow circles, five each way. Open and close your fingers.",
     None, 10, False, False, [], None, "Breathe normally."),
    ("shoulder-rolls", "Shoulder rolls",
     "Lift your shoulders toward your ears, roll them back and down. Five times. Then five times the other way.",
     None, 10, False, False, [], None, "In as they lift, out as they drop."),
    ("half-neck-rolls", "Half neck rolls",
     "Drop your chin toward your chest. Slowly roll your ear toward your right shoulder, back to the middle, then to the left. Do not roll your head backward.",
     None, 6, False, False, ["vertigo", "high_bp", "osteoporosis"], "Only move the head from side to side, slowly, eyes open.", "Breathe slowly and never hold your breath."),
    # --- standing with support ------------------------------------------------
    ("chair-tadasana", "Standing tall with a chair",
     "Stand behind your chair, both hands on the back. Feet hip-width apart. Stand tall and breathe. Sit down if you feel unsteady.",
     20, None, True, False, ["vertigo"], "Hold the chair with both hands and keep your eyes on one point.", "Breathe slowly."),
    ("chair-tree", "Tree with a chair",
     "Hold the chair with one hand. Rest the sole of your other foot against your ankle or calf, never against the knee. Stay a few breaths. Change sides.",
     8, 2, True, False, ["vertigo", "knee_replacement", "hip_replacement", "fall_risk"], "Keep your toes on the floor and only rest the heel against the other ankle.", "Breathe slowly."),
    ("chair-warrior-two", "Warrior two with a chair",
     "Stand sideways to the chair, holding it. Step your feet wide apart. Bend the front knee only a little. Stretch your free arm out. Come back and change sides.",
     8, 2, True, False, ["knee_replacement", "hip_replacement", "vertigo", "fall_risk", "osteoporosis"], "Keep the legs closer together and do not bend the knee at all.", "Breathe steadily."),
    ("calf-raises", "Calf raises holding a chair",
     "Hold the chair. Rise slowly onto your toes, pause, and come down slowly.",
     None, 8, True, False, ["vertigo", "fall_risk"], "Raise your heels only a little.", "Out as you rise, in as you lower."),
    ("side-leg-raise", "Side leg raise holding a chair",
     "Hold the chair. Lift one leg out to the side a little, keeping your body upright. Lower it slowly. Change legs.",
     None, 8, True, False, ["hip_replacement", "vertigo", "fall_risk"], "Lift only a hand's width.", "Breathe normally."),
    ("wall-walk", "Heel-to-toe walk along a wall",
     "Stand next to a wall with one hand on it. Walk slowly, placing the heel of one foot right in front of the toes of the other. Take six steps and come back.",
     None, 6, True, False, ["vertigo", "fall_risk"], "Walk with feet apart, as in normal walking, keeping a hand on the wall.", "Breathe normally."),
    ("wall-pushup", "Wall push-up",
     "Stand an arm's length from a wall. Put your hands on it at shoulder height. Bend your elbows to bring your chest toward the wall, then push back.",
     None, 8, True, False, ["high_bp", "recent_surgery", "hernia"], "Stand closer to the wall so the effort is smaller.", "Breathe out as you push away. Never hold your breath."),
    ("sit-to-stand", "Sit to stand",
     "Sit at the front of a sturdy chair. Cross your arms or hold your knees. Lean slightly forward and stand up slowly. Sit down slowly.",
     None, 5, True, False, ["fall_risk", "knee_replacement", "hip_replacement", "recent_surgery"], "Use your hands on the chair arms or your knees to help.", "Out as you stand, in as you sit."),
    # --- rest and breath ------------------------------------------------------
    ("easy-seat", "Easy seat",
     "Sit comfortably on a chair, or cross-legged on a cushion if that is comfortable. Rest your hands on your knees. Close your eyes if you like.",
     60, None, False, False, [], None, "Let the breath be natural."),
    ("savasana-bed", "Rest lying down",
     "Lie on your back on a bed or mat with a pillow under your head and knees if it helps. Let your arms rest by your sides. Rest and let go.",
     120, None, False, True, ["vertigo"], "Rest on the bed with your head raised on two pillows.", "Breathe naturally."),
    ("anulom-vilom", "Alternate nostril breathing",
     "Rest your left hand on your knee. Close your right nostril with your thumb and breathe in gently through the left. Close the left, open the right, breathe out. Continue slowly. Do not hold your breath.",
     None, 8, False, False, [], "Breathe through both nostrils and just count to four in, four out.", "Slow and gentle. No holding, no forcing."),
    ("bhramari", "Humming bee breath",
     "Sit tall. Breathe in through the nose. As you breathe out, make a soft humming sound like a bee. Feel the hum in your face. Rest and repeat.",
     None, 5, False, False, ["glaucoma"], "Keep your hands on your knees and do not press on your face.", "Long, easy out-breath."),
    ("belly-breathing", "Deep belly breathing",
     "Place one hand on your belly. Breathe in slowly and feel the hand rise. Breathe out and feel it fall. Do not force it.",
     None, 8, False, False, [], None, "Slow and easy. If you feel dizzy, breathe normally."),
]

# slug, title, description, kind, total_minutes, pose_slugs, targets, instructor
PROGRAMMES = [
    ("chair-yoga", "Chair yoga", "Whole-body movement, sitting down. Gentle and unhurried.", "chair_yoga", 10,
     ["easy-seat", "belly-breathing", "shoulder-rolls", "half-neck-rolls", "seated-cat-cow", "seated-side-stretch",
      "seated-twist", "seated-marching", "seated-leg-extension", "ankle-circles", "belly-breathing"], []),
    ("joint-mobility", "Joint mobility (Sukshma Vyayama)", "Slow circles from neck to ankles.", "mobility", 8,
     ["easy-seat", "half-neck-rolls", "shoulder-rolls", "wrist-circles", "seated-cat-cow", "seated-marching", "ankle-circles", "belly-breathing"], []),
    ("balance-fall-prevention", "Balance for fall prevention", "Standing with a chair or wall. Sit whenever you like.", "mobility", 10,
     ["chair-tadasana", "calf-raises", "side-leg-raise", "wall-walk", "chair-tree", "sit-to-stand", "belly-breathing"], []),
    ("morning-energiser", "Morning energiser", "Seated and standing movement with breath.", "chair_yoga", 12,
     ["easy-seat", "belly-breathing", "shoulder-rolls", "seated-eagle-arms", "seated-side-stretch", "seated-twist",
      "chair-tadasana", "calf-raises", "seated-marching", "anulom-vilom"], []),
    ("back-care", "Back care", "Gentle extensions and stretches for a stiff back.", "chair_yoga", 10,
     ["easy-seat", "seated-cat-cow", "seated-side-stretch", "seated-twist", "seated-forward-bend", "seated-butterfly", "belly-breathing"], []),
    ("pranayama", "Pranayama", "Breath practice. Slow, gentle and never forced.", "pranayama", 8,
     ["easy-seat", "belly-breathing", "anulom-vilom", "bhramari", "belly-breathing"], []),
    ("evening-wind-down", "Evening wind-down", "Slow stretches, then rest.", "chair_yoga", 12,
     ["easy-seat", "shoulder-rolls", "seated-side-stretch", "seated-butterfly", "ankle-circles", "belly-breathing", "bhramari", "savasana-bed"], []),
    ("surya-namaskar-chair", "Surya Namaskar, chair version", "The twelve steps, adapted to a chair.", "chair_yoga", 9,
     ["easy-seat", "seated-mountain", "seated-eagle-arms", "seated-side-stretch", "seated-forward-bend", "seated-cat-cow",
      "seated-leg-extension", "seated-mountain", "belly-breathing"], []),
]
