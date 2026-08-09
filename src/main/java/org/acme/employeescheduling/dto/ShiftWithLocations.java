package org.acme.employeescheduling.dto;



import java.util.List;
import org.acme.employeescheduling.domain.Shift;


/**
 * @brief Associates a shift with its list of available locations.
 *
 * @details This data transfer object bundles a {@link Shift} together with a list of
 *          {@link Location} objects that are applicable to that shift. It is used for
 *          transferring shift-location associations between layers of the application.
 *
 * @author acme
 * @version 1.0
 */
public class ShiftWithLocations {

    /** @brief The shift associated with the locations. */
    private Shift shift;

    /** @brief The list of locations available for this shift. */
    private List<Location> locations;

    /**
     * @brief Constructs a ShiftWithLocations with the specified shift and locations.
     *
     * @param shift     The shift.
     * @param locations The list of locations associated with the shift.
     */
    public ShiftWithLocations(Shift shift, List<Location> locations) {
        this.shift = shift;
        this.locations = locations;
    }

    /**
     * @brief Gets the shift.
     *
     * @return The shift.
     */
    public Shift getShift() {
        return shift;
    }

    /**
     * @brief Sets the shift.
     *
     * @param shift The shift to set.
     */
    public void setShift(Shift shift) {
        this.shift = shift;
    }

    /**
     * @brief Gets the list of locations.
     *
     * @return The list of locations.
     */
    public List<Location> getLocations() {
        return locations;
    }

    /**
     * @brief Sets the list of locations.
     *
     * @param locations The list of locations to set.
     */
    public void setLocations(List<Location> locations) {
        this.locations = locations;
    }

}
