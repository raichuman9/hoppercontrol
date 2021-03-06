package edu.cmu.cs.graphics.hopper.control;

import edu.cmu.cs.graphics.hopper.VecUtils;
import org.jbox2d.callbacks.DebugDraw;
import org.jbox2d.collision.shapes.CircleShape;
import org.jbox2d.collision.shapes.PolygonShape;
import org.jbox2d.common.Color3f;
import org.jbox2d.common.Transform;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.*;
import org.jbox2d.dynamics.contacts.Contact;
import org.jbox2d.dynamics.joints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/** Holds body state and runs control logic for biped hopper avatar */
public class BipedHopper extends Avatar<BipedHopperControl> {
    private static final Logger log = LoggerFactory.getLogger(BipedHopper.class);

    static DecimalFormat numFormat = new DecimalFormat( "#,###,###,##0.000" );

    public enum ControlState {
        FLIGHT,
        LOAD,
        COMPRESS,
        THRUST,
        UNLOAD
    }

    final int NUM_LEGS = 2;

    private final float HIP_PROP_GAIN = 5000.0f;
    private final float HIP_DRAG_GAIN = 1000.0f;

//    private final float THRUST_SPRING_FREQUENCY = 50.0f; //Hz
//    private final float THRUST_SPRING_DAMPING_RATIO = 0.8f;
//    private final float HOP_SPRING_FREQUENCY = 20.0f; //Hz
//    private final float HOP_SPRING_DAMPING_RATIO = 0.2f;

    private final float THRUST_SPRING_PROP_GAIN = 20000.0f;
    private final float THRUST_SPRING_DRAG_GAIN = 200.0f;
    private final float THRUST_SPRING_EXPONENT = 1.1f;
    private final float HOP_SPRING_PROP_GAIN = 3000.0f;
    private final float HOP_SPRING_DRAG_GAIN = 100.0f;
    private final float HOP_SPRING_EXPONENT = 1.0f;

    private final Vec2 CHASSIS_SIZE = new Vec2(1f, 0.5f);
    private final float CHASSIS_DENSITY = 2.0f;

    private final Vec2 HIP_SIZE = new Vec2(0.3f, 0.1f);
    private final float HIP_DENSITY = CHASSIS_DENSITY * 5;
    private final float UPPER_LEG_DEFAULT_LENGTH = 3.0f;

    private final Vec2 KNEE_SIZE = new Vec2(0.2f, 0.1f);
    private final float KNEE_DENSITY = CHASSIS_DENSITY * 5;
    private final float LOWER_LEG_DEFAULT_LENGTH = 2.0f;

    private final float FOOT_RADIUS = 0.2f;
    private final float FOOT_DENSITY = CHASSIS_DENSITY * 5;

    protected boolean m_footInContact;
    protected boolean m_chassisInContact;

    public float m_springVel;
    public ControlState m_controlState;
    public Vec2 m_bodyVel;
    public float m_bodyPitch;

    public float m_currFlightPeriod;
    public float m_currStancePeriod;             //running count of time length of current support period (or 0 if not in support)
    public float m_nextStancePeriodEst;          //estimate of length of next period that active leg is touching ground

    protected boolean m_lastHopAmpRecorded;      //reset at moment of takeoff, set once we record peak of hop
    public float m_lastHopAmp;                   //hopping amplitude, as measured at peak of prior flight period

    //Joints (arrays where each index corresponds to one of the legs)
    public RevoluteJoint m_hipJoint[];
    public PrismaticJoint m_thrustJoint[];    //keeps hip & knee aligned along leg direction
    public PrismaticJoint m_springJoint[];     //keeps knee & foot aligned along leg direction

    //Since Box2D uses initial joint lengths as reference 0 translation, we need to store that
    //value if we'd like to use "absolute" lengths of a joint
    protected float m_initThrustJointLength[];
    protected float m_initSpringJointLength[];

    protected Vec2 m_initPos = new Vec2();
    protected Vec2 m_initVel = new Vec2();
    protected Body m_chassis;
    protected Body m_hip[];
    protected Body m_knee[];
    protected Body m_foot[];
    protected List<Body> m_bodies;
    protected List<Joint> m_joints;

    protected ControlProvider<BipedHopperControl> m_controlProvider;

    public float m_targetActiveHipAngle = 0.0f;     //target angle of active hip relative to body
    public float m_targetdIdleHipAngle = 0.0f;      //target angle of idle hip relative to body
    public float m_targetThrustSpringLength[];      //target lengths of upper leg thrust spring for each leg
    public float m_targetHopSpringLength[];         //target lengths of lower leg hopping spring for each leg

    //Torques/forces on various joints (readable; modified internally)
    public float m_activeHipTorque = 0.0f;
    public float m_idleHipTorque = 0.0f;

    public int m_activeLegIdx;
    public int m_idleLegIdx;

    public BipedHopper() {
        m_footInContact = false;
        m_chassisInContact = false;
        m_springVel = 0;
        m_controlState = ControlState.FLIGHT;
        m_bodyVel = new Vec2();
        m_bodyPitch = 0.0f;
        m_activeLegIdx = 0; m_idleLegIdx = 1;

        m_currFlightPeriod = 0.0f;
        m_currStancePeriod = 0.0f;
        m_nextStancePeriodEst = 0.2f; //TODO: What's a reasonable init value for this?

        m_lastHopAmpRecorded = false;
        m_lastHopAmp = 0.0f;

        m_bodies = new ArrayList<Body>();
        m_joints = new ArrayList<Joint>();
        m_hip = new Body[NUM_LEGS];
        m_knee = new Body[NUM_LEGS];
        m_foot = new Body[NUM_LEGS];
        m_hipJoint = new RevoluteJoint[NUM_LEGS];
        m_thrustJoint = new PrismaticJoint[NUM_LEGS];
//        m_thrustSpring = new DistanceJoint[NUM_LEGS];
        m_springJoint = new PrismaticJoint[NUM_LEGS];
//        m_hopSpring = new DistanceJoint[NUM_LEGS];

        m_initThrustJointLength = new float[NUM_LEGS];
        m_initSpringJointLength = new float[NUM_LEGS];

        m_targetThrustSpringLength = new float[NUM_LEGS];
        m_targetHopSpringLength = new float[NUM_LEGS];

        //Reasonable init vals
        for (int i = 0; i < NUM_LEGS; i++)
            m_targetThrustSpringLength[i] = UPPER_LEG_DEFAULT_LENGTH;

        //Arbitrary... really... just out of the way of any world objects
        setInitState(new Vec2(-2.0f, 6.0f), new Vec2(0.0f, 0.0f));

        //Default control provider
        ControlProvider<BipedHopperControl> controlProvider = new ControlProvider<BipedHopperControl>(new BipedHopperControl());
        setControlProvider(controlProvider);
    }

    @Override
    public List<Body> getBodies() {return m_bodies;}

    @Override
    public List<? extends Joint> getJoints() {return m_joints;}

    @Override
    public Body getMainBody() {return m_chassis;}

    public PrismaticJoint getActiveSpringJoint() {return m_springJoint[m_activeLegIdx];}

    /** Returns body which should be monitored for contact with ground in order to update
     * "inContact" flag */
    public Body getGroundContactBody() {return m_foot[m_activeLegIdx];}

    public ControlState getControlState() {return m_controlState;}

    public boolean isFootInContact() {return m_footInContact;}
    protected void setFootInContact(boolean val) {m_footInContact = val;}

    public boolean isChassisInContact() {return m_chassisInContact;}
    protected void setChassisInContact(boolean val) {m_chassisInContact = val;}

    @Override
    public void setInitState(Vec2 initPos, Vec2 initVel) {
        m_initPos.set(initPos);
        m_initVel.set(initVel);
    }

    @Override
    public void init(World world) {
        // Chassis
        {
            PolygonShape shape = new PolygonShape();
            shape.setAsBox(CHASSIS_SIZE.x, CHASSIS_SIZE.y);

            FixtureDef sd = new FixtureDef();
            sd.density = CHASSIS_DENSITY;
            sd.shape = shape;
            sd.filter.groupIndex = -1;
            BodyDef bd = new BodyDef();
            bd.type = BodyType.DYNAMIC;
            bd.position.set(m_initPos);
            m_chassis = world.createBody(bd);
            m_chassis.createFixture(sd);
            m_bodies.add(m_chassis);
        }

        for (int i = 0; i < NUM_LEGS; i++) {
            // Hip
            {
                PolygonShape shape = new PolygonShape();
                shape.setAsBox(HIP_SIZE.x, HIP_SIZE.y);

                FixtureDef sd = new FixtureDef();
                sd.density = HIP_DENSITY;
                sd.shape = shape;
                sd.filter.groupIndex = -1;
                BodyDef bd = new BodyDef();
                bd.type = BodyType.DYNAMIC;
                bd.position.set(m_initPos);
                m_hip[i] = world.createBody(bd);
                m_hip[i].createFixture(sd);
                m_bodies.add(m_hip[i]);
            }


            //Knee
            {
                PolygonShape shinShape = new PolygonShape();
                shinShape.setAsBox(KNEE_SIZE.x, KNEE_SIZE.y);
                FixtureDef fd = new FixtureDef();
                fd.density = KNEE_DENSITY;
                fd.shape = shinShape;
                fd.filter.groupIndex = -1;

                BodyDef bd = new BodyDef();
                bd.type = BodyType.DYNAMIC;
                bd.position.x =  m_hip[i].getPosition().x;
                bd.position.y =  m_hip[i].getPosition().y - UPPER_LEG_DEFAULT_LENGTH;   //offset along thigh
                m_knee[i] = world.createBody(bd);
                m_knee[i].createFixture(fd);
                m_bodies.add(m_knee[i]);
            }

            //Foot
            {
                CircleShape footShape = new CircleShape();
                footShape.setRadius(FOOT_RADIUS);
                FixtureDef capFd = new FixtureDef();
                capFd.density = FOOT_DENSITY;
                capFd.shape = footShape;
                capFd.friction = 100.0f; //high friction so we don't have to worry much about slipping
                capFd.filter.groupIndex = -1;

                BodyDef bd = new BodyDef();
                bd.type = BodyType.DYNAMIC;
                bd.position.x =  m_hip[i].getPosition().x;
                bd.position.y =  m_knee[i].getPosition().y - LOWER_LEG_DEFAULT_LENGTH;   //offset to bottom of shin
                m_foot[i] = world.createBody(bd);
                m_foot[i].createFixture(capFd);
                m_bodies.add(m_foot[i]);
            }

            //Rotary hip joint
            {
                RevoluteJointDef jd = new RevoluteJointDef();

                jd.initialize(m_chassis, m_hip[i], m_initPos);
                jd.collideConnected = false;
    //            jd.enableLimit = true;
    //            jd.lowerAngle = jd.upperAngle = 0.0f;
                m_hipJoint[i] = (RevoluteJoint) world.createJoint(jd);
                m_joints.add(m_hipJoint[i]);
            }

            //Linear upper leg thrust
            {
                {
                    PrismaticJointDef jd = new PrismaticJointDef();
                    //Note the ordering: We want the knee to be the "main" body for correct spring direction
                    jd.initialize(m_knee[i], m_hip[i], m_hip[i].getPosition().add(m_knee[i].getPosition()).mulLocal(0.5f), new Vec2(0, 1.0f));
                    jd.collideConnected = false;
                    jd.enableMotor = true;
                    m_thrustJoint[i] = (PrismaticJoint) world.createJoint(jd);
                    m_initThrustJointLength[i] = m_thrustJoint[i].getBodyB().getPosition().sub(m_thrustJoint[i].getBodyA().getPosition()).length();
                    m_joints.add(m_thrustJoint[i]);
                }
            }

            //Lower leg spring
            {
                //Constrain motion to lie along hopper leg axis
                {
                    PrismaticJointDef jd = new PrismaticJointDef();
                    //Note the ordering: We want the foot to be the "main" body for correct spring direction
                    jd.initialize(m_foot[i], m_knee[i], m_knee[i].getPosition().add(m_foot[i].getPosition()).mulLocal(0.5f), new Vec2(0, 1.0f));
                    jd.collideConnected = false;
                    jd.enableMotor = true;
                    jd.enableLimit = true;
                    //Mechanical stop: Prevent spring from shrinking below some static length
                    jd.lowerTranslation = -LOWER_LEG_DEFAULT_LENGTH;
                    jd.upperTranslation = 0;
                    m_springJoint[i] = (PrismaticJoint) world.createJoint(jd);
                    m_initSpringJointLength[i] = m_springJoint[i].getBodyB().getPosition().sub(m_springJoint[i].getBodyA().getPosition()).length();
                    m_targetHopSpringLength[i] = m_initSpringJointLength[i];
                    m_joints.add(m_springJoint[i]);
                }
            }
        }

        //Apply initial velocity to each body part
        for (Body b : getBodies())  {
            b.getLinearVelocity().set(m_initVel);
        }
    }

    @Override
    public void setControlProvider(ControlProvider<BipedHopperControl> provider) {
        this.m_controlProvider = provider;
    }

    @Override
    public ControlProvider<BipedHopperControl> getControlProvider() {
        return m_controlProvider;
    }

    public BipedHopperControl getCurrentControl() {
        return m_controlProvider.getCurrControl();
    }

    @Override
    public void update(float dt) {
        //Update sensor values
        if (m_springJoint != null)
            m_springVel = m_springJoint[m_activeLegIdx].getJointSpeed();
        m_bodyVel = m_chassis.getLinearVelocity();
        m_bodyPitch = m_chassis.getAngle();

        //Check for control FSM transitions
        switch (m_controlState) {
            case FLIGHT:
                //Switch to load or compress state once we make contact with ground
                if (m_footInContact)        {
                    m_controlState = ControlState.COMPRESS; //TODO: include "LOAD" phase as well?
                    m_currStancePeriod = 0.0f;
                }
                break;
            case COMPRESS:
                //Switch to thrusting once at or past fully compressed spring
                if (m_springVel > 0)                  {
                    m_controlState = ControlState.THRUST;

                    //Add thrust back by pushing down on spring
                    float lengthAtThrustStart = m_targetThrustSpringLength[m_activeLegIdx];
                    m_targetThrustSpringLength[m_activeLegIdx] = lengthAtThrustStart + m_controlProvider.getCurrControl().activeThrustDelta;
                }
                break;
            case THRUST:
                //Switch to flight once we leave the ground
                if (m_footInContact == false) {
                    m_controlState = ControlState.FLIGHT;
                    m_nextStancePeriodEst = m_currStancePeriod; //estimate next support from current
                    swapActiveLeg();
                    m_lastHopAmpRecorded = false;
                    m_currFlightPeriod = 0.0f;

                    //Advance to next control... this is a new hop
                    m_controlProvider.goToNextControl();
                }
                break;
        }

        //Run control logic based on FSM state
        switch (m_controlState) {
            case FLIGHT:
                servoLegPlacement(dt);
                //Record max height noce we've started falling
                if (!m_lastHopAmpRecorded && getMainBody().getLinearVelocity().y < 0){
                    m_lastHopAmpRecorded = true;
                    m_lastHopAmp = getMainBody().getPosition().y;
                }
                break;
            case LOAD:
                //TODO
                break;
            case COMPRESS:
                //TODO
                servoBodyPitch();
                break;
            case THRUST:
                servoBodyPitch();
                break;
            case UNLOAD:
                //TODO
                break;
        }

        if (m_footInContact)
            m_currStancePeriod += dt;
        else
            m_currFlightPeriod += dt;

        //Idle hip is constantly servoed throughout control cycle
        if (NUM_LEGS > 1)
            servoIdleHipPitch();

        //Do manual linear joint force update to emulate springiness
        for (int i = 0; i < NUM_LEGS; i++) {
            updateSpring(m_thrustJoint[i], m_targetThrustSpringLength[i] - m_initThrustJointLength[i], THRUST_SPRING_PROP_GAIN, THRUST_SPRING_DRAG_GAIN, THRUST_SPRING_EXPONENT);
            updateSpring(m_springJoint[i], m_targetHopSpringLength[i] - m_initSpringJointLength[i], HOP_SPRING_PROP_GAIN, HOP_SPRING_DRAG_GAIN, HOP_SPRING_EXPONENT);
        }
    }

    @Override
    public void onBeginContact(Contact contact) {
        //Start "in contact with ground" status if applicable
        Body groundContactBody = getGroundContactBody();
        Body bodyA = contact.getFixtureA().getBody();
        Body bodyB = contact.getFixtureB().getBody();

        if (bodyA == groundContactBody || bodyB == groundContactBody)
            setFootInContact(true);

        if (bodyA == m_chassis || bodyB == m_chassis)
            setChassisInContact(true);
    }

    @Override
    public void onEndContact(Contact contact) {
        //End "in contact with ground" status if applicable

        Body groundContactBody = getGroundContactBody();
        Body bodyA = contact.getFixtureA().getBody();
        Body bodyB = contact.getFixtureB().getBody();

        if (bodyA == groundContactBody || bodyB == groundContactBody)
            setFootInContact(false);

        if (bodyA == m_chassis || bodyB == m_chassis)
            setChassisInContact(false);
    }

    protected void swapActiveLeg() {
        if (NUM_LEGS == 2) {
            int temp = m_idleLegIdx;
            m_idleLegIdx = m_activeLegIdx;
            m_activeLegIdx = temp;
        }
    }

    protected void servoBodyPitch() {
        float targetActiveHipAngle = m_bodyPitch - m_controlProvider.getCurrControl().targetBodyPitch;
        m_activeHipTorque = servoTowardAngle(m_hipJoint[m_activeLegIdx], targetActiveHipAngle, HIP_PROP_GAIN, HIP_DRAG_GAIN);
    }

    protected void servoIdleHipPitch() {
        //Mirror the active hip by servo-ing to negative of its pitch
        float activeHipPitch = m_hipJoint[m_activeLegIdx].getJointAngle();
        m_targetdIdleHipAngle = -activeHipPitch;
        m_idleHipTorque = servoTowardAngle(m_hipJoint[m_idleLegIdx], m_targetdIdleHipAngle, HIP_PROP_GAIN, HIP_DRAG_GAIN);
    }

    protected void servoLegPlacement(float dt) {
        /////// LENGTHS /////////////////////////////////////////////////////////////////////////////
        //Retract idle leg, lengthen active for landing
        //(to make this gradual, use lerp on current value (hacky, but seems to work well))
        float alpha = Math.min(1.0f, 5.0f * dt);

        float idleLegTerminalLength = UPPER_LEG_DEFAULT_LENGTH + m_controlProvider.getCurrControl().idleThrustDelta;
        float activeLegTerminalLength = UPPER_LEG_DEFAULT_LENGTH;

//        if (NUM_LEGS > 1)
//            m_thrustSpring[m_idleLegIdx].setLength(lerp(m_thrustSpring[m_idleLegIdx].getLength(), idleLegTargetLength, alpha));
//        m_thrustSpring[m_activeLegIdx].setLength(lerp(m_thrustSpring[m_activeLegIdx].getLength(), activeLegTargetLength, alpha));

        m_targetThrustSpringLength[m_activeLegIdx] = lerp(m_targetThrustSpringLength[m_activeLegIdx], activeLegTerminalLength, alpha);
        if (NUM_LEGS > 1)
            m_targetThrustSpringLength[m_idleLegIdx] = lerp(m_targetThrustSpringLength[m_idleLegIdx], idleLegTerminalLength, alpha);
        /////////////////////////////////////////////////////////////////////////////////////////////

        /////// ANGLE /////////////////////////////////////////////////////////////////////////////
        //Set leg position using hip based on desired landing location
        float deltaFromTargetVel = m_bodyVel.x - m_controlProvider.getCurrControl().targetBodyVelX;
        float desiredLandingOffsetX = (0.5f * m_bodyVel.x * m_nextStancePeriodEst) + (m_controlProvider.getCurrControl().targetBodyVelXLegPlacementGain * deltaFromTargetVel);

        //Bound to some reasonable range
        float maxAllowedOffsetX = 0.5f * activeLegTerminalLength;
        if (desiredLandingOffsetX > maxAllowedOffsetX)
            desiredLandingOffsetX = maxAllowedOffsetX;
        if (desiredLandingOffsetX < -maxAllowedOffsetX)
            desiredLandingOffsetX = -maxAllowedOffsetX;

        m_targetActiveHipAngle =  0.0f;
        float eps = 0.000001f;
        if (Math.abs(desiredLandingOffsetX) > eps)
            m_targetActiveHipAngle = -m_bodyPitch + (float)(Math.asin(desiredLandingOffsetX/activeLegTerminalLength));

        if (Float.isNaN(m_targetActiveHipAngle))
            m_targetActiveHipAngle = 0.0f;

        m_activeHipTorque = servoTowardAngle(m_hipJoint[m_activeLegIdx], m_targetActiveHipAngle, HIP_PROP_GAIN, HIP_DRAG_GAIN);
        /////////////////////////////////////////////////////////////////////////////////////////////
    }

    //Returns (1-alpha)*x + alpha*y
    protected float lerp(float x, float y, float alpha) {
        return (1-alpha)*x + alpha*y;
    }

    /** Calculates servo torque for revolute joint toward given angle using specified gains.
     * Returns applied torque. */
    protected float servoTowardAngle(RevoluteJoint joint, float targetAngle, float propGain, float dragGain)     {
        float jointAngle = joint.getJointAngle();
        float jointSpeed = joint.getJointSpeed();
        float targetJointDelta = targetAngle - jointAngle;

        final float BIG_NUMBER = Float.MAX_VALUE;

        float torque = propGain*targetJointDelta - dragGain*jointSpeed;

        //Hacky, but get joint to use our torque by setting our torque as max and forcing use of max torque
        //by setting some arbitrarily large velocity in servo direction
        float absTorque = Math.abs(torque);
        float signTorque = Math.signum(torque);
        joint.enableMotor(true);
        joint.setMaxMotorTorque(absTorque);
        joint.setMotorSpeed(signTorque > 0 ? BIG_NUMBER : -BIG_NUMBER);

        return torque;
    }


    protected float updateLinearSpring(PrismaticJoint joint, float restLength, float propGain, float dragGain) {
        return updateSpring(joint, restLength, propGain, dragGain, 1);
    }

    /** Updates velocity on given prismatic joint to create "spring" effect, as outlined here:
     *   http://www.box2d.org/forum/viewtopic.php?f=3&t=1007
     *   Returns applied force set on the joint.
     *   (And, yes, this is very similar to "servoTowardAngle") */
    protected float updateSpring(PrismaticJoint joint, float restLength, float propGain, float dragGain, float exponent)     {
        float x = joint.getJointTranslation();
        float velX = joint.getJointSpeed();
        float deltaX = restLength - x;
        float deltaXSign = Math.signum(deltaX);

        final float BIG_NUMBER = Float.MAX_VALUE;

        float propForceMag = (float)Math.pow(Math.abs(propGain*deltaX), exponent);
        float force = deltaXSign*propForceMag - dragGain*velX;

        //Hacky, but get joint to use our force by setting our force as max and requiring use of max force
        //by setting some arbitrarily large velocity in servo direction
        float absForce = Math.abs(force);
        float signForce = Math.signum(force);
        joint.enableMotor(true);
        joint.setMaxMotorForce(absForce);
        joint.setMotorSpeed(signForce > 0 ? BIG_NUMBER : -BIG_NUMBER);

        return force;
    }

    @Override
    public void appendDebugTextLines(List<String> lines, List<Color3f> colors) {
        super.appendDebugTextLines(lines, colors);

        lines.add("Control State: " + getControlState());                                                               colors.add(Color3f.WHITE);
        lines.add("Active Leg Spring Compression: " + numFormat.format(getActiveSpringJoint().getJointTranslation()));  colors.add(Color3f.WHITE);
        lines.add("Body Ang Vel: " + numFormat.format(getMainBody().getAngularVelocity()));                             colors.add(Color3f.WHITE);
        lines.add("Body Vel X: " + numFormat.format(getMainBody().getLinearVelocity().x));                              colors.add(Color3f.WHITE);
        lines.add("Target Body Vel X: " + numFormat.format(getCurrentControl().targetBodyVelX));                        colors.add(Color3f.WHITE);
        lines.add("Vel X Leg Gain: " + numFormat.format(getCurrentControl().targetBodyVelXLegPlacementGain));           colors.add(Color3f.WHITE);
        lines.add("Thrust offset: " + numFormat.format(getCurrentControl().activeThrustDelta));                         colors.add(Color3f.WHITE);
        lines.add("Last Hop Amp: " + numFormat.format(m_lastHopAmp));                                                   colors.add(Color3f.WHITE);
        lines.add("Flight period: " + numFormat.format(m_currFlightPeriod));                                            colors.add(Color3f.WHITE);
        lines.add("Stance period: " + numFormat.format(m_currStancePeriod));                                            colors.add(Color3f.WHITE);
        lines.add("Target Spring Length: " + numFormat.format(m_targetThrustSpringLength[m_activeLegIdx]));             colors.add(Color3f.WHITE);
    }

    @Override
    public void drawDebugInfo(DebugDraw dd) {
        Transform bodyTransform = getMainBody().getTransform();

        //Get target active & idle leg directions in world coords
        Vec2 targetActiveLegDir = new Vec2(0.0f,-1.0f);
        VecUtils.rotateLocal(targetActiveLegDir, m_targetActiveHipAngle);
        VecUtils.rotateLocal(targetActiveLegDir, m_bodyPitch);
        Vec2 activeLegTorqueLine = targetActiveLegDir.mul(m_activeHipTorque * 0.1f);
        dd.drawSegment(bodyTransform.p, bodyTransform.p.add(targetActiveLegDir), new Color3f(1,0,0));
//            dd.drawSegment(bodyTransform.p, bodyTransform.p.add(activeLegTorqueLine), new Color3f(1,0,1));

        Vec2 targetIdleLegDir = new Vec2(0.0f,-1.0f);
        VecUtils.rotateLocal(targetIdleLegDir, m_targetdIdleHipAngle);
        VecUtils.rotateLocal(targetIdleLegDir, m_bodyPitch);
        Vec2 idleLegTorqueLine = targetIdleLegDir.mul(m_idleHipTorque * 0.1f);
        dd.drawSegment(bodyTransform.p, bodyTransform.p.add(targetIdleLegDir), new Color3f(0,1,0));
//            dd.drawSegment(bodyTransform.p, bodyTransform.p.add(idleLegTorqueLine), new Color3f(0,1,1));

        //Draw user input helper visuals (ie: visualize upcoming control parameters)
        BipedHopperControl nextControl = m_controlProvider.getControlAtIdx(m_controlProvider.CurrControlIdx() + 1);
        if (nextControl == null) //if no next control explicitly given, current control will continue
            nextControl = m_controlProvider.getCurrControl();
        Vec2 controlP = bodyTransform.p.add(new Vec2(0, 2.0f));
        if (nextControl != null) {
            dd.drawSolidCircle(controlP, 0.1f, new Vec2(0,1), new Color3f(1,1,1));
            dd.drawSegment(controlP, controlP.add(new Vec2(nextControl.targetBodyVelX, 0.0f)), new Color3f(0,1,0));
            dd.drawSegment(controlP, controlP.add(new Vec2(0.0f, nextControl.activeThrustDelta * 10.0f)), new Color3f(0,1,1));
        }
        //Compare current velocity to target
        final float lineEps = -0.1f;
        dd.drawSegment(controlP.add(new Vec2(0,lineEps)), controlP.add(new Vec2(getMainBody().getLinearVelocity().x, lineEps)), new Color3f(1,1,0));
    }

    @Override
    public Object getState() {
        BipedHopperState s = new BipedHopperState();
        s.x = this.getMainBody().getPosition();
        s.xdot = this.getMainBody().getLinearVelocity();

        s.theta = this.getMainBody().getAngle();
        s.thetadot = this.getMainBody().getAngularVelocity();

        s.joints = new float[this.getJoints().size()];
        s.jointVels = new float[this.getJoints().size()];
        for (int i = 0; i < this.getJoints().size(); i++) {
            Joint joint = this.getJoints().get(i);
            if (joint instanceof RevoluteJoint) {
                s.joints[i]  = ((RevoluteJoint)joint).getJointAngle();
                s.jointVels[i] = ((RevoluteJoint)joint).getJointSpeed();
            }
            else if (joint instanceof PrismaticJoint) {
                s.joints[i] = ((PrismaticJoint)joint).getJointTranslation();
                s.jointVels[i] = ((PrismaticJoint)joint).getJointSpeed();
            }
            else {
               log.error("WARNING: Biped hopper state creation is skipping over a joint that was neither prismatic nor revolute");
            }
        }

        s.controlState = this.getControlState();

        return s;
    }

}
