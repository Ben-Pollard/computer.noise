package midi

import javax.sound.midi.{MidiDevice, MidiEvent, MidiSystem, Receiver, Sequence, ShortMessage}
import models.Primitives.{Bar, BarSequence, Duration, ParallelBarSequences}

import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool


object Sequencer {

  type Command = java.lang.Integer
  val noteOn: Command = 144
  val noteOff: Command = 128

  type OnOff = (Option[(ShortMessage, ShortMessage)], Long)
  type MonophonicSequence = Seq[OnOff]
  type PolyphonicSequence = Seq[MonophonicSequence]
  type SequenceOfSequences = Seq[PolyphonicSequence]
  type Arrangement = Seq[SequenceOfSequences]

  def getDevice(midiDeviceString: String): MidiDevice = {
    val devices = MidiSystem.getMidiDeviceInfo().toVector
    println(s"Available Devices: ${devices.mkString("; ")}")

    try {
      val chosenDeviceString = devices.filter(_.getName()==midiDeviceString).last
      MidiSystem.getMidiDevice(chosenDeviceString)
    } catch {
      case e: java.lang.UnsupportedOperationException => throw new IllegalArgumentException(s"Specified device $midiDeviceString not found")
    }

  }

  def apply(parallelBars: Seq[BarSequence], bpm:Int, midiDeviceString: String = "Gervill") = {

    //init receiver
    val device = getDevice(midiDeviceString)
    device.open()
    val receiver = device.getReceiver


    //init sequence
//    var sequencer = MidiSystem.getSequencer
//    sequencer.open()
//    var sequence = new Sequence(Sequence.PPQ, 1, 1) //divisionType, resolution in PPQ (ticks per quarter note), numTracks

    //We have 3 nested seqs. Outer=bars, they happen sequentially. Middle=seq of monophonic sequences, they happen concurrently
    val arrangement: Arrangement = parallelBars.map { bars =>
      bars.bars.map { bar =>
        bar.notes.map { s =>
          val monophonicSequence = s.map { n =>
            val ms: Long = (n.duration * 60.0 * 1000.0 / bpm.toDouble).toLong
            if (n.pitch.isDefined) {
              //translate note duration to ms
              val onMessage = new ShortMessage()
              val offMessage = new ShortMessage()
              onMessage.setMessage(noteOn, 1, n.pitch.get, n.velocity) //command, channel, note, velocity
              offMessage.setMessage(noteOff, 1, n.pitch.get, n.velocity) //command, channel, note, velocity
              (Some(onMessage, offMessage), ms)
            } else (None, ms)
          }
          monophonicSequence
        }
      }
    }


    def monophonicSequencer(monophonicSequence: MonophonicSequence): Unit = {
      for (m <- monophonicSequence) {
        if (m._1.isDefined) {
          receiver.send(m._1.get._1, -1)
        }

        Thread.sleep(m._2)

        if (m._1.isDefined) {
          receiver.send(m._1.get._2, -1)
        }
      }
    }

    //Plays the contents of a bar. Executes each phrase in parallel.
    def polyphonicSequencer(polyphonicSequence: PolyphonicSequence): Unit = {
      val pc = polyphonicSequence.par
      pc.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(polyphonicSequence.length))
      pc.map(s => monophonicSequencer(s))
    }

    //Plays sequences of bars
    def sequencePlayer(sequenceOfSequences: SequenceOfSequences): Unit = {
      for (sequence <- sequenceOfSequences) {
        polyphonicSequencer(sequence)
      }
    }

    //Plays the bar sequences in parallel
    def arrangementPlayer(arrangement: Arrangement): Unit = {
      val parallelism = arrangement.map(s => s.map(p => p.length).max).max * arrangement.length
      val pc = arrangement.par
      pc.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(parallelism))
      pc.map(layer => sequencePlayer(layer))

    }


    arrangementPlayer(arrangement)

    receiver.close()
    device.close()

  }
}