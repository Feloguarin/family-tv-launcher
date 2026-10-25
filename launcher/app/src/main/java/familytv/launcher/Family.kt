package familytv.launcher

/**
 * Who this launcher is for. Edit these for your family: the names show on screen, and the profile settings
 * decide which streaming profile a launch picks (see TvPlayback's recipes).
 */
object Family {
    /** The two kids, shown with a moon and a sun on the Home and kids screens. */
    const val KID_MOON = "Moon"
    const val KID_SUN = "Sun"
    const val KIDS = "$KID_MOON & $KID_SUN"

    /** Used in Claude's prompt for the kids' educational picks. */
    const val KIDS_AGE = 5

    /** Disney+ profile used for every launch: the label its "Who's watching?" screen reads out is "Access <name>'s profile". */
    const val DISNEY_PROFILE = "Parent"

    /** Netflix's "Who's watching?" list, top to bottom: where the parents' and the kids' profiles are. */
    const val NETFLIX_PARENT_ROW = 0
    const val NETFLIX_KIDS_ROW = 1
}
