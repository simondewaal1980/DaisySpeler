package nl.simon.daisyspeler.data.website

import kotlinx.serialization.Serializable

/** Websites that can be opened in the in-app book browser. */
@Serializable
enum class AudiobookSite(
  val title: String,
  val startUrl: String,
  val description: String,
) {
  PASSEND_LEZEN(
    title = "Passend Lezen",
    startUrl = "https://www.passendlezen.nl/",
    description = "Log in en kies een gesproken boek uit de bibliotheek.",
  ),
  ABC_GLOBAL_BOOKS(
    title = "ABC Global Books",
    startUrl = "https://abcglobalbooks.org/",
    description = "Log in en zoek een toegankelijk boek in de internationale catalogus.",
  ),
}
