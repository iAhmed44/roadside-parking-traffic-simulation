package traffic.interfaces;

/**
 * Simple idea: Anything that can move or be stopped on the street.
 * 
 * - `move()` is the instruction to go forward a little bit.
 * - `stop()` is the instruction to stop moving.
 *
 * - Nothing here actually moves cars. This file just says: "Any toy
 * that says it is `Movable` must have these two buttons: move and stop."
 */
public interface Movable {
    // Press this to make the object take one step forward (implementation decides
    // how).
    void move();

    // Press this to make the object stop moving (implementation decides how).
    void stop();
}
