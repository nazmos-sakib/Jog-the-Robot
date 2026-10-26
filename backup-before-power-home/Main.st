(*********************************************************************************
 * Copyright: B&R Industrial Automation GmbH
 * Author:    huebnern
 * Created:   January 23, 2020/8:20 AM
 * Edited:    October 23, 2026 - phone jog via ESP32 (action RemoteJog)
 *********************************************************************************)
//Main control of the system
PROGRAM _INIT

	//Init Status error
	MainPar.VisuPars.InError 	:= FALSE;
	MainPar.VisuPars.NotInError	:= TRUE;

	//Init Jog velocity
	JogVelocity 				:= INIT_JOG_VELOCITY;

	//Allow jog (Icmp_Ping task is not used)
	AllowJog := TRUE;

	Teach_Pos[0].Pos.X						:=	240;
	Teach_Pos[0].Pos.Y						:= 	0;
	Teach_Pos[0].Pos.Z						:=	335;
	Teach_Pos[0].Orient.Angle1				:=	0;
	Teach_Pos[0].Orient.Angle2				:=	0;

	Teach_Pos[1].Pos.X := 20.699866696484403;
	Teach_Pos[1].Pos.Y := -16.763507571369498;
	Teach_Pos[1].Pos.Z	:= 680.95033173265676;
	Teach_Pos[1].Orient.Angle1 := -126.89365872204304;
	Teach_Pos[1].Orient.Angle2 := 140.99818452000619;

	Teach_Pos[2].Pos.X := -69.244968642624457;
	Teach_Pos[2].Pos.Y := 56.0771029659347;
	Teach_Pos[2].Pos.Z	:= 672.76899259820129;
	Teach_Pos[2].Orient.Angle1 := -111.97058904528618;
	Teach_Pos[2].Orient.Angle2 := 140.99818452000619;

	Teach_Pos[3].Pos.X := 240;
	Teach_Pos[3].Pos.Y := 0;
	Teach_Pos[3].Pos.Z	:= 335;
	Teach_Pos[3].Orient.Angle1 := 0;
	Teach_Pos[3].Orient.Angle2 := 0;

END_PROGRAM

PROGRAM _CYCLIC
	Aktor := Sensor;
	//Error Reset
	fbMpRobotic5Arm.ErrorReset				:= MainPar.Cmd.ErrorReset;

	//Write status
	StatusMain;

	//Change visualization vars
	VisuDisable;

	//Phone jog via ESP32 (BLE -> Modbus TCP). Must run before the state machine.
	RemoteJog;

	//Power On
	IF MainPar.Cmd.PowerOn THEN
		IF fbMpRobotic5Arm.Info.ReadyToPowerOn AND MainPar.StateMachine = STATE_Disabled THEN
			MainPar.StateMachine 	:= STATE_PowerOn;
		END_IF
	ELSE
		MainPar.StateMachine 		:= STATE_Disabled;
	END_IF

	//---------------------------------------------
	//-------------State Machine-------------------
	//---------------------------------------------
	CASE MainPar.StateMachine OF

		//--------State Disabled-------------------
		STATE_Disabled:

			//Power robot off
			fbMpRobotic5Arm.Power 			:= FALSE;

			//Set PreStateIdent
			MainPar.PreStateIdent 			:= STATE_Disabled;

			//Reset Error as long as the robot is disabled
			MainPar.VisuPars.InError 	:= FALSE;
			MainPar.VisuPars.NotInError	:= TRUE;

		//--------State PowerOn-------------------
		STATE_PowerOn:

			//Power robot on
			fbMpRobotic5Arm.Power 			:= TRUE;

			IF MainPar.Status = Idle THEN
				MainPar.StateMachine 		:= STATE_WaitForHome;
			END_IF

		//--------State WaitForHome-------------------
		STATE_WaitForHome:

			//Set PreStateIdent
			MainPar.PreStateIdent 			:= STATE_WaitForHome;

			//Reset Stop
			IF fbMpRobotic5Arm.Stop THEN
				fbMpRobotic5Arm.Stop 		:= FALSE;
			END_IF

			IF MainPar.Cmd.Home THEN
				MainPar.StateMachine 	:= STATE_Home;
				MainPar.HomingCount		:= NoHoming;
				//Reset command
				MainPar.Cmd.Home 		:= FALSE;
			END_IF

		//--------State Home-------------------
		STATE_Home:

			fbMpRobotic5Arm.Home := TRUE;

			IF MainPar.HomingCount = NoHoming THEN
				ChangeHomingMode := FALSE;
			ELSIF MainPar.HomingCount = DefaultHomingDone THEN
				ChangeHomingMode := TRUE;
			END_IF

			IF EDGEPOS(fbMpRobotic5Arm.IsHomed) THEN
				MainPar.HomingCount 		:= MainPar.HomingCount + 1;
				fbMpRobotic5Arm.Home 		:= FALSE;
			END_IF

			IF MainPar.HomingCount = DirectHomingDone THEN
				MainPar.StateMachine 		:= STATE_Idle;
				fbMpRobotic5Arm.Home 		:= FALSE;
			ELSIF MainPar.Cmd.Stop THEN
				MainPar.StateMachine 		:= STATE_WaitForHome;
				fbMpRobotic5Arm.Stop 		:= TRUE;
				fbMpRobotic5Arm.Home 		:= FALSE;
				//Reset command
				MainPar.Cmd.Stop 			:= FALSE;
			END_IF

		//--------State Idle-------------------
		STATE_Idle:

			//Set PreStateIdent
			MainPar.PreStateIdent 			:= STATE_Idle;

			//Reset Stop
			IF fbMpRobotic5Arm.Stop THEN
				fbMpRobotic5Arm.Stop 		:= FALSE;
			END_IF

			IF MainPar.Cmd.Home THEN
				MainPar.HomingCount			:= NoHoming;
				MainPar.StateMachine 		:= STATE_Home;

			ELSE

				//Change to GCode state in case of visu is currently on the G-Code page
				IF (MainPar.GoToState.GCode) AND EDGEPOS(MainPar.Cmd.Start) THEN
					MainPar.StateMachine 	:= STATE_GCode;
				END_IF

				//Change to Tipping state in case of visu is on the Tipping page or the phone is jogging
				IF (MainPar.GoToState.Tipping) AND (JogDirectionSet) THEN
					MainPar.StateMachine 	:= STATE_Tipping;
				END_IF

				//Change to MoveSetPos state in case of visu is currently on the MoveSetPos page
				IF (MainPar.GoToState.MoveSetPos) AND EDGEPOS(MainPar.Cmd.Start) THEN
					MainPar.StateMachine 	:= STATE_MoveSetPos;
				END_IF

			END_IF

		//--------State G-Code-------------------
		STATE_GCode:

			//Start movement
			fbMpRobotic5Arm.MoveProgram 		:= TRUE;

			//Stop movement
			IF MainPar.Cmd.Stop THEN
				fbMpRobotic5Arm.Stop 			:= TRUE;
				fbMpRobotic5Arm.MoveProgram 	:= FALSE;
				MainPar.StateMachine 			:= STATE_Idle;
			ELSIF fbMpRobotic5Arm.MoveDone THEN
				fbMpRobotic5Arm.MoveProgram 	:= FALSE;
				MainPar.StateMachine 			:= STATE_Idle;
			END_IF

		//--------State Tipping-------------------
		STATE_Tipping:

			//Change coordinate system
			ParRobot.CoordSystem 			:= mcACS;

			//Set velocity for chosen axis with action
			Tipping;

			//Call Jog
			IF AllowJog THEN
				fbMpRobotic5Arm.Jog := TRUE;
			ELSE
				fbMpRobotic5Arm.Jog := FALSE;
			END_IF

			IF NOT JogDirectionSet THEN
				MainPar.StateMachine 		:= STATE_Idle;
				fbMpRobotic5Arm.Jog 		:= FALSE;
			END_IF

		//--------State MoveSetPos-------------------
		STATE_MoveSetPos:

			//Change coordinate system
			ParRobot.CoordSystem 				:= mcMCS;

			//Start movement
			IF MainPar.Cmd.MoveIndex THEN
				fbMpRobotic5Arm.MoveDirect 	:= TRUE;
			ELSIF NOT MainPar.Cmd.MoveIndex THEN
				fbMpRobotic5Arm.MoveLinear 	:= TRUE;
			END_IF

			//Stop movement
			IF MainPar.Cmd.Stop THEN
				fbMpRobotic5Arm.Stop 			:= TRUE;
				fbMpRobotic5Arm.MoveDirect 	:= FALSE;
				fbMpRobotic5Arm.MoveLinear 	:= FALSE;
				MainPar.StateMachine 			:= STATE_Idle;
			ELSIF fbMpRobotic5Arm.MoveDone THEN
				fbMpRobotic5Arm.MoveDirect 	:= FALSE;
				fbMpRobotic5Arm.MoveLinear 	:= FALSE;
				MainPar.StateMachine 			:= STATE_Idle;
			END_IF

		//--------State Error-------------------
		STATE_Error:

			//Set output var for visu
			MainPar.VisuPars.InError 		:= TRUE;
			MainPar.VisuPars.NotInError		:= FALSE;

			//Stop movement
			fbMpRobotic5Arm.Stop 			:= TRUE;
			fbMpRobotic5Arm.MoveDirect 	:= FALSE;
			fbMpRobotic5Arm.MoveLinear 	:= FALSE;
			fbMpRobotic5Arm.Jog 			:= FALSE;
			fbMpRobotic5Arm.MoveProgram 	:= FALSE;
			fbMpRobotic5Arm.Home 			:= FALSE;

			//Go out of the error state as soon as no error is active
			IF MainPar.Status <> Error THEN
				MainPar.StateMachine 		:= MainPar.PreStateIdent;
				fbMpRobotic5Arm.Stop 		:= FALSE;
				MainPar.VisuPars.InError 	:= FALSE;
				MainPar.VisuPars.NotInError	:= TRUE;
				MainPar.Cmd.ErrorReset		:= FALSE;
			END_IF

	END_CASE

END_PROGRAM

PROGRAM _EXIT

END_PROGRAM
