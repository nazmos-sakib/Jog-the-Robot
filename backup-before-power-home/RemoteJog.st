(*********************************************************************************
 * Action RemoteJog (task Main)
 * Lets the Android app jog the robot through the ESP32 (BLE -> Modbus TCP).
 * The ESP32 (194.94.86.15) is read by the existing Modbus device "ModbusTcp_any":
 *   BleHeartbeat (IR0), BleEnable (IR1), BleAxis (IR2), BleDir (IR3)
 * Called in Main _CYCLIC right after "VisuDisable;".
 *********************************************************************************)
ACTION RemoteJog: 

	// 1. Watchdog: the ESP32 heartbeat must change at least every 500 ms
	IF BleHeartbeat <> BleHeartbeatOld THEN
		BleHeartbeatOld 			:= BleHeartbeat;
		BleWatchdog.IN 				:= FALSE;
	ELSE
		BleWatchdog.IN 				:= TRUE;
	END_IF
	BleWatchdog.PT 					:= T#500ms;
	BleWatchdog();
	BleLinkOk 						:= NOT BleWatchdog.Q;

	// 2. The phone owns the jog while the link is ok and the phone is connected
	IF BleLinkOk AND BleEnable = 1 THEN
		
		BleRemoteActive 			:= TRUE;
		MainPar.GoToState.Tipping 	:= TRUE;

		// Select the axis the same way the panel does (ImageIndex ranges ax1..ax5)
		CASE BleAxis OF
			1: IF ImageIndex < ax1 OR ImageIndex > ax1pos THEN ImageIndex := ax1; END_IF
			2: IF ImageIndex < ax2 OR ImageIndex > ax2pos THEN ImageIndex := ax2; END_IF
			3: IF ImageIndex < ax3 OR ImageIndex > ax3pos THEN ImageIndex := ax3; END_IF
			4: IF ImageIndex < ax4 OR ImageIndex > ax4pos THEN ImageIndex := ax4; END_IF
			5: IF ImageIndex < ax5 OR ImageIndex > ax5pos THEN ImageIndex := ax5; END_IF
		END_CASE

		// 1 = positive (R), 2 = negative (L), anything else = stop
		JogDirection.Positive 		:= (BleDir = 1) AND (BleAxis >= 1) AND (BleAxis <= 5);
		JogDirection.Negative 		:= (BleDir = 2) AND (BleAxis >= 1) AND (BleAxis <= 5);

	ELSIF BleRemoteActive THEN
		
		// Link lost or phone gone: stop and give control back to the panel
		BleRemoteActive 			:= FALSE;
		JogDirection.Positive 		:= FALSE;
		JogDirection.Negative 		:= FALSE;
		MainPar.GoToState.Tipping 	:= FALSE;
		
	END_IF

END_ACTION
