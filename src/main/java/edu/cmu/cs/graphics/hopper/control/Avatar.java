package edu.cmu.cs.graphics.hopper.control;

import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.joints.Joint;

import java.util.List;

/** A controllable character */
public abstract class Avatar {
    /**Initializes physical simulation components of this avatar in given world */
    public abstract void init(World world);

    /** Returns list of all bodies belonging to the avatar */
    public abstract List<Body> getBodies();

    /** Returns list of all joints belonging to the avatar */
    public abstract List<? extends Joint> getJoints();

    /** Returns body which is considered the root of this avatar */
    public abstract Body getMainBody();

    /** Sets control that will be employed by this avatar at next update */
    public abstract void setCurrentControl(Control control);

    public abstract Control getCurrentControl();

    /**Applies any update logic (eg: control torques) for this avatar for a simulation timestep delta (in seconds) */
    public abstract void update(float dt);
}
