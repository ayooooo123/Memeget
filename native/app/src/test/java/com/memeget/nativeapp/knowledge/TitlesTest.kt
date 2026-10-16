package com.memeget.nativeapp.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class TitlesTest {
    /** A unit vector with the given components and the rest of its length on axis [rest]. */
    private fun vec(rest: Int, vararg parts: Pair<Int, Float>): FloatArray {
        val v = FloatArray(512)
        var sum = 0f
        for ((axis, value) in parts) { v[axis] = value; sum += value * value }
        v[rest] = sqrt(1f - sum)
        return v
    }

    private fun title(name: String, category: String, axis: Int, names: List<String> = emptyList(), characters: List<String> = emptyList()) =
        CuratedLabelDef(name, "a scene from $name", category, names + characters, vec(axis), names)

    private fun person(id: Long, image: FloatArray, labels: Set<String> = emptySet(), rejected: Set<String> = emptySet()) =
        LookalikeVote.LabeledMeme(id, image, labels, rejected)

    private val sopranos = title("The Sopranos", Titles.TV, 0, names = listOf("sopranos"), characters = listOf("tony soprano"))
    private val groove = title("The Emperor's New Groove", Titles.MOVIE, 1)
    private val madMen = title("Mad Men", Titles.TV, 2)
    private val pokemon = title("Pokémon", Titles.TV, 3)
    private val office = title("The Office", Titles.TV, 8)

    @Test fun labelsNameTitlesWhateverTheirSpelling() {
        val titles = Titles(listOf(sopranos, groove, madMen, pokemon, office), emptyList(), emptyList(), emptyList())
        assertEquals(groove, titles.titleNamed("emperors new groove"))
        assertEquals(madMen, titles.titleNamed("madmen"))
        assertEquals(pokemon, titles.titleNamed("pokemon"))
        assertEquals(sopranos, titles.titleNamed("Sopranos"))
        assertEquals(office, titles.titleNamed("the office"))
        assertNull("one word after \"the\" can be an everyday word", titles.titleNamed("office"))
        assertNull("a character's name is not the show's", titles.titleNamed("Tony Soprano"))
        assertNull(titles.titleNamed("pepe"))
    }

    @Test fun aMemeGetsATitleOnlyWhenItClearlyBeatsTheNext() {
        val titles = Titles(listOf(sopranos, madMen), emptyList(), emptyList(), emptyList())
        assertEquals(sopranos, titles.identify(vec(100, 0 to 0.22f, 2 to 0.18f), 9)?.title)
        assertNull("0.01 ahead is too close to call", titles.identify(vec(100, 0 to 0.20f, 2 to 0.19f), 9))
        assertNull("under the margin", titles.identify(vec(100, 0 to 0.16f), 9))
    }

    @Test fun aPersonsLabelOrAnExactCopyOfItGivesTheTitle() {
        val picture = vec(100, 0 to 0.05f)
        val titles = Titles(listOf(sopranos, madMen), emptyList(), listOf(person(1, picture, labels = setOf("sopranos"))), emptyList())
        val labeled = titles.identify(picture, 1)
        assertEquals(sopranos, labeled?.title)
        assertTrue(labeled!!.named)
        assertTrue("the person's own \"sopranos\" stands for the show", labeled.personsTag)
        assertEquals(sopranos, titles.identify(picture, 2)?.title)
        assertFalse("a copy carries no person's tag", titles.identify(picture, 2)!!.personsTag)
    }

    @Test fun aCharactersNameGivesTheShowButANotAboutItSaysNothingAboutTheShow() {
        val tony = vec(100, 0 to 0.05f)
        val notTony = vec(101, 0 to 0.25f)
        val notTheShow = vec(102, 0 to 0.25f)
        val titles = Titles(
            listOf(sopranos, madMen), emptyList(),
            listOf(
                person(1, tony, labels = setOf("tony soprano")),
                person(2, notTony, rejected = setOf("tony soprano")),
                person(3, notTheShow, rejected = setOf("sopranos")),
            ),
            emptyList(),
        )
        val given = titles.identify(tony, 1)
        assertEquals(sopranos, given?.title)
        assertFalse("\"tony soprano\" stays a tag of its own beside the show", given!!.personsTag)
        assertEquals(sopranos, titles.identify(notTony, 2)?.title)
        assertNull(titles.identify(notTheShow, 3))
    }

    @Test fun aTitleRejectedOnAMemeLetsTheNextOneWin() {
        val picture = vec(100, 0 to 0.25f, 2 to 0.22f)
        val titles = Titles(listOf(sopranos, madMen), emptyList(), listOf(person(1, picture, rejected = setOf("the sopranos"))), emptyList())
        assertEquals(madMen, titles.identify(picture, 1)?.title)
        assertEquals(madMen, titles.question(picture, 1))
    }

    @Test fun aCopyOfALabeledMemeGetsNoTitleWhenANotExampleLiesNear() {
        val picture = vec(100, 0 to 0.05f)
        // A "not The Sopranos" teaching at cosine 0.90 to the copy.
        val unlike = vec(101, 0 to 0.05f * 0.9f, 100 to sqrt(1f - 0.05f * 0.05f) * 0.9f)
        val titles = Titles(
            listOf(sopranos, madMen), emptyList(),
            listOf(person(1, picture, labels = setOf("sopranos"))),
            listOf(Titles.Teaching(unlike, "the sopranos", positive = false)),
        )
        assertEquals("the person's own label still counts", sopranos, titles.identify(picture, 1)?.title)
        assertNull(titles.identify(picture, 2))
    }

    // South Park memes look alike (cosine 0.80) and each matches the prompt at only 0.12.
    private val southPark = title("South Park", Titles.TV, 4)
    private val futurama = title("Futurama", Titles.TV, 5)
    private fun southParkMeme(n: Int) = vec(200 + n, 4 to 0.12f, 6 to 0.8863f)
    private fun futuramaMeme(n: Int) = vec(300 + n, 5 to 0.12f, 7 to 0.8863f)

    private fun lookalikes(teachings: List<Titles.Teaching> = emptyList()) = Titles(
        listOf(southPark, futurama), emptyList(),
        (1..3).map { person(it.toLong(), southParkMeme(it), labels = setOf("south park")) } +
            (11..12).map { person(it.toLong(), futuramaMeme(it), labels = setOf("futurama")) },
        teachings,
    )

    @Test fun examplesLiftATitleOnceTheyHeldUpOnLabeledMemes() {
        val titles = lookalikes()
        assertEquals(mapOf("South Park" to (3 to 0), "Futurama" to (2 to 0)), titles.heldOut)
        val hit = titles.identify(southParkMeme(4), 4)
        assertEquals(southPark, hit?.title)
        assertTrue(!hit!!.named)
        assertNull("two held-out picks are not enough to learn from", titles.identify(futuramaMeme(13), 13))
    }

    @Test fun aNearbyCounterExampleRulesTheTitleOut() {
        // A "not South Park" teaching at cosine 0.90 to the new meme.
        val unlike = vec(400, 4 to 0.12f * 0.9f, 6 to 0.8863f * 0.9f, 204 to 0.4472f * 0.9f)
        val titles = lookalikes(listOf(Titles.Teaching(unlike, "south park", positive = false)))
        assertNull(titles.identify(southParkMeme(4), 4))
    }

    @Test fun titleTagsSayWhetherTheyAreATvShowOrAMovie() {
        val titles = Titles(listOf(sopranos, groove), emptyList(), emptyList(), emptyList())
        val movie = titles.identify(vec(100, 1 to 0.25f), 9)!!.tag
        assertEquals("the emperor's new groove", movie.label)
        assertEquals(Titles.MOVIE, movie.category)
        assertEquals(Titles.SOURCE, movie.source)
        assertEquals(Titles.TV, titles.identify(vec(100, 0 to 0.25f), 9)!!.tag.category)
    }

    @Test fun aLikelyTitleIsAskedAboutUntilAPersonGivesOne() {
        val picture = vec(100, 0 to 0.16f)
        val asked = Titles(listOf(sopranos, madMen), emptyList(), emptyList(), emptyList())
        assertNull("not enough to tag", asked.identify(picture, 1))
        assertEquals(sopranos, asked.question(picture, 1))
        val answered = Titles(listOf(sopranos, madMen), emptyList(), listOf(person(1, picture, labels = setOf("the sopranos"))), emptyList())
        assertNull(answered.question(picture, 1))
    }
}
