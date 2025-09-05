---

### **Project Documentation: AR Walking Navigation for the Visually Impaired**

#### **1. Project Overview**

This Android application provides real-time, voice-guided walking navigation for visually impaired users by leveraging Augmented Reality (AR) and Google Maps (Routes API) services. The user can speak a desired destination type (e.g., "cafe," "supermarket"), and the application will find the nearest matching location, calculate a walking route, and provide continuous spoken instructions overlaid on the live camera feed.

The system is designed with a primary focus on safety and usability, using a combination of environmental understanding through AR, precise location data, and clear, actionable voice feedback. It uses a custom-built navigation engine that integrates several APIs to deliver a seamless user experience.

---

#### **2. System Diagram**

The application's architecture is event-driven and modular, with clear separation of responsibilities between components.

```
+---------------------------+       +-------------------------+
|     User (Voice Input)    |------>|    HelloArActivity      |
+---------------------------+       | (UI & Conductor)        |
                                    +-----------+-------------+
                                                |
                                    +-----------+-----------+
                                    |                       |
+----------------v----------------+  |  +----------------v----------------+
|        VoiceAssistant           |  |  |         PathFinder            |
| (Speech-to-Text, Text-to-Speech)|  |  | (Planner & API Requester)     |
+---------------------------------+  |  +----------------+----------------+
                                     |                   | (Places & Routes APIs)
                                     |                   v
+----------------v----------------+  |  +----------------+----------------+
|         GuidanceState           |<-+--|      Google Cloud Services      |
| (Shared Instruction Holder)     |  |  +---------------------------------+
+----------------+----------------+  |
                 |                   |
+----------------v----------------+  |
|         HelloArRenderer         |<-+
| (AR, VLM, & Snapshot Handler)   |
+----------------+----------------+
                 |
+----------------v----------------+
|        GuidanceManager          |<---- (Live Location Updates)
| (Live Navigation Engine)        |
+---------------------------------+

```

**Component Breakdown:**

*   **HelloArActivity:** The main Android Activity. It acts as the central "conductor," owning all major components and orchestrating the flow of data between them. It manages the UI, lifecycle, and permissions.
*   **VoiceAssistant:** A self-contained helper class responsible for all audio interactions. It handles both listening for voice commands (Speech-to-Text) and speaking instructions (Text-to-Speech).
*   **PathFinder:** The "planner." When given a keyword, it communicates with Google's **Places API** to find the nearest relevant location and then with the **Routes API** to get a complete walking route plan (polyline and text steps).
*   **GuidanceManager:** The live navigation "engine." It holds the route plan from `PathFinder` and continuously compares it against the user's live location updates to determine the correct instruction for the current moment.
*   **GuidanceState:** A simple, thread-safe data holder that stores the current instruction string. It acts as a bridge, allowing the `GuidanceManager` to update the instruction and the `HelloArRenderer` to read it without them needing to know about each other.
*   **HelloArRenderer:** The heart of the AR experience. It runs on the high-frequency GL Thread, rendering the camera feed and any virtual objects. It is also responsible for periodically capturing the camera image and map snapshot, reading the current instruction from `GuidanceState`, and sending all three to the Visual Language Model (VLM) for real-time environmental analysis.

---

#### **3. System Inputs**

The system takes in data from multiple sources, both from the user and the environment.

##### **3.1. Primary User Input:**
*   **Voice Command (String):** The user's spoken request for a destination (e.g., "take me to a pharmacy"). This is the primary trigger for the navigation workflow.

##### **3.2. Sensor and API Inputs:**
*   **Live Camera Feed (Image Stream):** Continuous video from the device's camera, processed by ARCore to understand the world. A frame from this stream is sent to the VLM.
*   **Device Location (GPS & Fused Location):** Continuous latitude, longitude, and bearing updates from the `FusedLocationProviderClient`. This is the core input for the `GuidanceManager`.
*   **Device Orientation (IMU):** ARCore uses the Inertial Measurement Unit to track the phone's rotation and orientation in 3D space.
*   **Map Snapshot (Image):** A top-down 2D image of the current map view, showing the user's position relative to the calculated route. This is sent to the VLM for contextual understanding.
*   **Places API Response (JSON):** A list of nearby places that match the user's voice query, received by the `PathFinder`.
*   **Routes API Response (JSON):** A detailed route plan, including a polyline and a list of turn-by-turn text steps, received by the `PathFinder`.

---

#### **4. System Outputs**

The system produces several forms of output designed to guide the user effectively and safely.

##### **4.1. Primary User Output:**
*   **Spoken Guidance (Audio):** The primary output for the user. The `VoiceAssistant` speaks the turn-by-turn instructions calculated by the `GuidanceManager` and the real-time environmental analysis from the VLM.
*   **Haptic Feedback (Vibration):** A vibration is triggered whenever a new, important instruction is generated by the VLM, providing a non-auditory cue to the user.

##### **4.2. Visual Outputs (Primarily for Sighted Assistance and Debugging):**
*   **AR View (Screen):** The live camera feed is shown on the screen.
*   **Map Overlay (Screen):** A 2D map is displayed as an overlay, showing the user's position (blue dot/arrow) and the calculated route (blue line).
*   **Instruction Text (Screen):** The current turn-by-turn instruction is displayed in a `TextView` overlay for visual confirmation.

##### **4.3. Data Output:**
*   **VLM Request (HTTP POST):** A JSON payload containing the Base64-encoded camera image, map snapshot, and the current high-level navigation instruction. This is sent from the `HelloArRenderer` to the external VLM server.

### **Installation**
* Install Android Studio https://developer.android.com/studio
* Clone this project ``` git clone https://github.com/Alonelymess/BlindHelperApp.git ```
* Connect an Android phone using cable or through wifi (Using Android Studio, turn on the developer mode in your phone first)
* In the local.properties file, add these variables:
  ```bash
  MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
  ```
* Press the run button as you're all set
