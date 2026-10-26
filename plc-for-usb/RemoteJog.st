(*********************************************************************************
 * Action RemoteJog (task Main)  -  version 2: jog + power + home + stop + safety interlocks
 * Lets the Android app control the robot through the ESP32 (BLE -> Modbus TCP).
 * The ESP32 (194.94.86.15) is read and written by the Modbus device "ModbusTcp_any":
 *   read  (FC4):  BleHeartbeat, BleEnable, BleAxis, BleDir, BleCmd, BleCmdSeq
 *   write (FC16): BleStateOut, BleHbEcho
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

	// 2. Who has control? The phone takes over while it is connected, but never
	//    in the middle of a running program: then it only watches, and takes
	//    over as soon as the program has ended.
	BleProgramRunning 				:= (MainPar.StateMachine = STATE_GCode) OR (MainPar.StateMachine = STATE_MoveSetPos);
	
	IF BleLinkOk AND BleEnable = 1 THEN
		IF NOT BleRemoteActive AND NOT BleProgramRunning THEN
			BleRemoteActive 		:= TRUE;
		END_IF
	ELSIF BleRemoteActive THEN
		// Link lost or phone gone: stop and give control back to the panel
		BleRemoteActive 			:= FALSE;
		JogDirection.Positive 		:= FALSE;
		JogDirection.Negative 		:= FALSE;
		MainPar.GoToState.Tipping 	:= FALSE;
	END_IF

	// Fresh press: a jog only counts if the button was pressed while the robot
	// was Ready (or already jogging). A button that was held while a program ran
	// or while homing is ignored until it is released and pressed again.
	IF BleDir = 0 THEN
		BleJogArmed 				:= TRUE;
	ELSIF NOT (MainPar.StateMachine = STATE_Idle OR MainPar.StateMachine = STATE_Tipping) THEN
		BleJogArmed 				:= FALSE;
	END_IF

	// 3. Jog
	IF BleRemoteActive THEN
		
		// Tells the panel "jog mode": this hides its Start/Stop buttons, so no
		// program can be started while the phone has control.
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
		JogDirection.Positive 		:= BleJogArmed AND (BleDir = 1) AND (BleAxis >= 1) AND (BleAxis <= 5);
		JogDirection.Negative 		:= BleJogArmed AND (BleDir = 2) AND (BleAxis >= 1) AND (BleAxis <= 5);
		
	END_IF

	// 4. Robot commands (power, home). The ESP32 counts BleCmdSeq up by one for
	//    every tap in the app; we act once on every new number.
	IF NOT BleLinkOk THEN
		// After a lost link (or a PLC/ESP32 restart) the first value is only
		// remembered, never executed, so an old command can't run by surprise.
		BleSeqSynced 				:= FALSE;
	ELSIF NOT BleSeqSynced THEN
		BleCmdSeqOld 				:= BleCmdSeq;
		BleSeqSynced 				:= TRUE;
	ELSIF BleCmdSeq <> BleCmdSeqOld THEN
		BleCmdSeqOld 				:= BleCmdSeq;
		CASE BleCmd OF
			1: // Power on
				MainPar.Cmd.PowerOn := TRUE;
			2: // Power off, but never while the robot is moving
				IF NOT (BleProgramRunning OR MainPar.StateMachine = STATE_Tipping OR MainPar.StateMachine = STATE_Home) THEN
					MainPar.Cmd.PowerOn := FALSE;
					BleHomeRequest 	:= FALSE;
				END_IF
			3: // Home, only when the robot can home
				IF MainPar.StateMachine = STATE_WaitForHome OR MainPar.StateMachine = STATE_Idle THEN
					BleHomeRequest 	:= TRUE;
				END_IF
			4: // Stop a running program, move or homing
				IF BleProgramRunning OR MainPar.StateMachine = STATE_Home THEN
					BleStopRequest 	:= TRUE;
				END_IF
		END_CASE
	END_IF

	// Stop works like the panel's Stop push button: Cmd.Stop stays TRUE until
	// the state machine has left the program/move/homing state, then it is
	// released. Once the robot is Ready again, the phone takes control (step 2).
	IF BleStopRequest THEN
		IF BleProgramRunning OR MainPar.StateMachine = STATE_Home THEN
			MainPar.Cmd.Stop 		:= TRUE;
		ELSE
			MainPar.Cmd.Stop 		:= FALSE;
			BleStopRequest 			:= FALSE;
		END_IF
	END_IF

	// Home works like the panel's push button: Cmd.Home stays TRUE until the
	// state machine has entered STATE_Home, then it is released again.
	IF BleHomeRequest THEN
		IF MainPar.StateMachine = STATE_Home OR MainPar.StateMachine = STATE_Disabled THEN
			MainPar.Cmd.Home 		:= FALSE;
			BleHomeRequest 			:= FALSE;
		ELSE
			MainPar.Cmd.Home 		:= TRUE;
		END_IF
	END_IF

	// 5. Feedback to the ESP32 (and from there to the app)
	CASE MainPar.StateMachine OF
		STATE_Disabled:		BleStateOut := 0;
		STATE_PowerOn:		BleStateOut := 5;
		STATE_WaitForHome:	BleStateOut := 9;
		STATE_Home:			BleStateOut := 10;
		STATE_Idle:			BleStateOut := 20;
		STATE_GCode:		BleStateOut := 30;
		STATE_Tipping:		BleStateOut := 40;
		STATE_MoveSetPos:	BleStateOut := 50;
		STATE_Error:		BleStateOut := 99;
	END_CASE
	BleHbEcho 						:= BleHeartbeat;	// proves to the ESP32 that the PLC is running

END_ACTION
