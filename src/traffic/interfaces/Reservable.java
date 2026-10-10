package traffic.interfaces;

/**
 * Something that can be reserved ahead of time, then released or consumed.
 *
 * <p>Phase 2 of this project uses it for curb parking: a {@code ParkingSpot}
 * implements {@code Reservable} so the {@code TrafficController} can hold a
 * spot for one specific car before that car arrives. That is the core of the
 * "smart/managed parking" improvement over Phase 1, where spots were grabbed
 * first-come on arrival with no reservation.
 *
 * <ul>
 *   <li>{@code reserve(owner)} — try to hold this for {@code owner}; returns
 *       {@code false} if it is already taken or reserved by someone else.</li>
 *   <li>{@code release()} — give up the hold so others can reserve it.</li>
 *   <li>{@code isReserved()} — true while a hold is active.</li>
 * </ul>
 */
public interface Reservable {

    /** Try to hold this object for {@code owner}. Returns false if unavailable. */
    boolean reserve(Object owner);

    /** Drop the current hold so the object becomes free again. */
    void release();

    /** True while this object is being held for some owner. */
    boolean isReserved();
}
