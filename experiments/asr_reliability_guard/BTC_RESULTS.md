# BTC English 100 — Risk-Aware ASR Reliability Guard

Replay output đã lưu, 100 câu/run, gồm 99 inference và một VAD skip EV-2748. Không chạy ASR mới, không sửa transcript, không đổi WER. Reference chỉ dùng để chấm và inspect sau quyết định guard.

Parakeet trong bảng này là v3 Transformers của lượt EN100 đã đóng băng. Các lần nghe/tăng âm lượng v2 NeMo là thí nghiệm riêng, không gộp với số liệu dưới đây.

## Kết quả single-pass thực tế

Không có word/token confidence trong logs. Giữ `confidence=null`; span HIGH/CRITICAL thiếu evidence → RETRY_ASR. Đây là yêu cầu chạy lại còn chờ, không phải đã chạy retry. Câu rỗng/VAD skip → ASK_REPEAT trong API guard; app production vẫn giữ logic NO_SPEECH hiện tại.

| Run | N | ACCEPT | RETRY_ASR | ASK_REPEAT | WER raw | WER normalized (frozen) | ASR sai vẫn ACCEPT (normalized) | Đúng nhưng chưa ACCEPT |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| app | 100 | 58 | 41 | 1 | 15.22% | 10.74% | 39 | 13 |
| small_hf | 100 | 58 | 41 | 1 | 15.12% | 10.80% | 41 | 12 |
| medium | 100 | 58 | 41 | 1 | 13.36% | 8.46% | 35 | 19 |
| turbo | 100 | 58 | 41 | 1 | 13.86% | 9.29% | 36 | 17 |
| parakeet | 100 | 58 | 41 | 1 | 44.09% | 7.07% | 27 | 19 |

WER raw được tính bằng scorer cũ. Normalized WER/per-sample operations được đọc lại từ `evidence/parent/comparison.json` sau khi xác minh SHA256 manifest/result và exact reference/hypothesis; không cài dependency, không chạy normalizer khác. `whisper-normalizer==1.0.1` hiện không có trong Python của máy này. Raw WER và normalized WER đều phải được đọc: Parakeet EV-0358 lặp “two” 666 lần có thể bị normalization che mất.

## Span kích hoạt và false alarm

**app:** `not` (CRITICAL_NEGATION, 9 câu/10 spans), `one` (CRITICAL_NUMBER, 5 câu/5 spans), `no` (CRITICAL_NEGATION, 4 câu/5 spans), `don't` (CRITICAL_NEGATION, 3 câu/3 spans), `set` (HIGH_ACTION, 2 câu/2 spans), `10` (CRITICAL_NUMBER, 2 câu/2 spans), `12` (CRITICAL_NUMBER, 2 câu/2 spans), `two` (CRITICAL_NUMBER, 2 câu/2 spans), `1900` (CRITICAL_NUMBER, 1 câu/1 spans), `2000` (CRITICAL_NUMBER, 1 câu/1 spans), `90` (CRITICAL_NUMBER, 1 câu/1 spans), `67297` (CRITICAL_NUMBER, 1 câu/1 spans)

- False alarm/pending rõ theo oracle: EV-0228 → RETRY_ASR, hypothesis `Did I remember to set a reminder alarm for my next doctor's appointment?`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0358 → RETRY_ASR, hypothesis `734 Main Street, Harper Valley, Oregon, 67297.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0383 → RETRY_ASR, hypothesis `So what I think we could do is maybe if we book you in for a face-to-face appointment in one of our clinics, it's not an emergency.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.

**small_hf:** `not` (CRITICAL_NEGATION, 9 câu/10 spans), `one` (CRITICAL_NUMBER, 5 câu/5 spans), `no` (CRITICAL_NEGATION, 4 câu/5 spans), `don't` (CRITICAL_NEGATION, 3 câu/3 spans), `set` (HIGH_ACTION, 2 câu/2 spans), `10` (CRITICAL_NUMBER, 2 câu/2 spans), `12` (CRITICAL_NUMBER, 2 câu/2 spans), `two` (CRITICAL_NUMBER, 2 câu/2 spans), `1900` (CRITICAL_NUMBER, 1 câu/1 spans), `2000` (CRITICAL_NUMBER, 1 câu/1 spans), `90` (CRITICAL_NUMBER, 1 câu/1 spans), `6` (CRITICAL_NUMBER, 1 câu/1 spans)

- False alarm/pending rõ theo oracle: EV-0228 → RETRY_ASR, hypothesis `Did I remember to set a reminder alarm for my next doctor's appointment?`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0383 → RETRY_ASR, hypothesis `So what I think we could do is maybe if we book you in for a face-to-face appointment in one of our clinics, it's not an emergency.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0491 → RETRY_ASR, hypothesis `You'll just need roughly $10 for food and gas money. My friend is driving, so we usually all chip in a few dollars for gas money.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.

**medium:** `not` (CRITICAL_NEGATION, 10 câu/11 spans), `one` (CRITICAL_NUMBER, 5 câu/5 spans), `no` (CRITICAL_NEGATION, 5 câu/6 spans), `don't` (CRITICAL_NEGATION, 3 câu/3 spans), `set` (HIGH_ACTION, 2 câu/2 spans), `10` (CRITICAL_NUMBER, 2 câu/2 spans), `two` (CRITICAL_NUMBER, 2 câu/2 spans), `1990` (CRITICAL_NUMBER, 1 câu/1 spans), `2000` (CRITICAL_NUMBER, 1 câu/1 spans), `67297` (CRITICAL_NUMBER, 1 câu/1 spans), `734` (CRITICAL_NUMBER, 1 câu/1 spans), `emergency` (HIGH_ACTION, 1 câu/1 spans)

- False alarm/pending rõ theo oracle: EV-0157 → RETRY_ASR, hypothesis `Play only all music released between the year 1990 and 2000.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0228 → RETRY_ASR, hypothesis `Did I remember to set a reminder alarm for my next doctor's appointment?`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0358 → RETRY_ASR, hypothesis `734 Main Street, Harper Valley, Oregon 67297.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.

**turbo:** `not` (CRITICAL_NEGATION, 9 câu/10 spans), `one` (CRITICAL_NUMBER, 5 câu/5 spans), `no` (CRITICAL_NEGATION, 5 câu/6 spans), `don't` (CRITICAL_NEGATION, 3 câu/3 spans), `set` (HIGH_ACTION, 2 câu/2 spans), `10` (CRITICAL_NUMBER, 2 câu/2 spans), `12` (CRITICAL_NUMBER, 2 câu/2 spans), `two` (CRITICAL_NUMBER, 2 câu/2 spans), `1990` (CRITICAL_NUMBER, 1 câu/1 spans), `2000` (CRITICAL_NUMBER, 1 câu/1 spans), `67297` (CRITICAL_NUMBER, 1 câu/1 spans), `734` (CRITICAL_NUMBER, 1 câu/1 spans)

- False alarm/pending rõ theo oracle: EV-0157 → RETRY_ASR, hypothesis `Play only all music released between the year 1990 and 2000.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0228 → RETRY_ASR, hypothesis `Did I remember to set a reminder alarm for my next doctor's appointment?`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0358 → RETRY_ASR, hypothesis `734 Main Street, Harper Valley, Oregon 67297.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.

**parakeet:** `not` (CRITICAL_NEGATION, 8 câu/9 spans), `one` (CRITICAL_NUMBER, 5 câu/5 spans), `two` (CRITICAL_NUMBER, 5 câu/671 spans), `no` (CRITICAL_NEGATION, 5 câu/6 spans), `hundred` (CRITICAL_NUMBER, 4 câu/7 spans), `thirty` (CRITICAL_NUMBER, 4 câu/5 spans), `four` (CRITICAL_NUMBER, 3 câu/3 spans), `nine` (CRITICAL_NUMBER, 3 câu/4 spans), `seven` (CRITICAL_NUMBER, 3 câu/5 spans), `eight` (CRITICAL_NUMBER, 3 câu/3 spans), `don't` (CRITICAL_NEGATION, 3 câu/4 spans), `set` (HIGH_ACTION, 2 câu/2 spans)

- False alarm/pending rõ theo oracle: EV-0383 → RETRY_ASR, hypothesis `So um what I think we could do is um maybe if we book you in for a face to face appointment in one of our clinics, it's not an emergency.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-0491 → RETRY_ASR, hypothesis `You'll just need roughly ten dollars for food and gas money. My friend is driving, so we usually all chip in a few dollars for gas money.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.
- False alarm/pending rõ theo oracle: EV-1508 → RETRY_ASR, hypothesis `I'll look at it like when my usual working hours are like from twelve o'clock till three o'clock or something.`. Transcript đúng theo scorer nhưng thiếu confidence span quan trọng.

Đây là false alarm theo correctness oracle, chưa chứng minh ngưỡng tệ: log thiếu confidence khiến policy bảo thủ. BTC là hội thoại chung, không phải corpus an toàn công nghiệp; từ như “start”, “set”, “open” dễ kích hoạt ngoài ngữ cảnh vận hành. Không dùng các reference này để điều chỉnh confidence hay sửa output.

## ASR sai nhưng guard vẫn ACCEPT — cần đọc

### app: 39 câu có lỗi normalized

| ID | Reference | Hypothesis | Protected difference (posthoc) |
|---|---|---|---|
| EV-0333 | and my ears are buzzing a bit. I'm just, they're feeling a bit off. | and my ears are buzzing a bit, I'm just feeling a bit off. | none detected |
| EV-0523 | I'm sorry to hear that. Um, how old was he when that happened? | Sorry to hear that. How does it when that happens? | none detected |
| EV-0894 | OK, all right. And just moving on swiftly sir. Do you have any allergies at all? | Okay, all right. I'm just moving on swiftly sir. Do you have any allergies at all? | none detected |
| EV-1173 | am i going to have to worry about the snowplows burying my car in the driveway tuesday | Am I going to have to worry about a snow post bearing my car in the driveway to stay? | none detected |
| EV-1212 | Okay. And is the pain, is that, is it there all the time, or does it come and go? | Okay, and is there pain all the time or does it come and go? | none detected |
| EV-1229 | so we will send your payment to fossil gas is there anything else i can help you with | So we will send your payment to follow gas. Is there anything else that can help you with? | none detected |
| EV-1829 | no that's all i needed help with thank you very much for your help | Notice all needed help with. Thank you very much for your help. | first:no |
| EV-1863 | will the temperature stay about the same or is it going to get colder | with the temperature stay about the same or is it going to get colder? | none detected |
| EV-2177 | sure shame fair day and uh how long did you have mongey for | Yeah, shame. Fair day. And how long do you have the monkey for? | none detected |
| EV-2364 | the biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier uh remote controls are often lost in the room | The biggest frustrations that people found with regard to personal preferences were something that you mentioned earlier. Remote controls are often lost in the room. | none detected |
| EV-4177 | hi my name is michael wilson um i would like to transfer money between my accounts | Hi, my name is Michael Wilkins. I would like to transfer money between my accounts. | none detected |
| EV-4250 | OK, right, and with the diarrhea, how many times a day are you going to the toilet? | Okay, right. And with the diarrhea, how many times a day are you going to the toilet? | none detected |
| EV-4321 | okay um i'm going to read out the text now so we will understand what we are supposed to do | Okay, I'm going to read out the text now so we understand what you're supposed to do. | none detected |
| EV-4505 | surprising to me is like uh people give a lot of attention to modifying mobile phones like a real want to see a new launch or something like that | surprising thing is like people give a lot of attention to modifying mobile phones like every month you see a new launch or something like that | none detected |
| EV-5455 | Now, do you have those these symptoms right now or have they gone off now? | Now, do you have these symptoms right now or have they gone off now? | none detected |
| EV-5482 | Right, OK. Do you remember what you were doing at the time? | Right. Okay. Do you remember what you were doing at the time? | none detected |
| EV-5600 | yeah i want some quality time quality time with my friends outside like it's it's really nice but we will see | Yeah, I want to some quality time with my friends and outside like it's really nice. But we will see. | none detected |
| EV-5674 | can i get a recap of what is going on with sprint right now | Can you get a recap of what is going on with the sprint right now? | none detected |
| EV-5761 | Have you measured it recently, either with an iWatch or something similar? | Have you measured it recently, either with an eye watch or something similar? | none detected |
| EV-6239 | OK. That's perfect. Thank you so much for your help. | Okay, that's perfect. Thank you so much for your help. | none detected |
| EV-6426 | easy to use has always has become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use | Easy to use has always become another major interest that with the whiteboard we can see that it's a remote that's easy to use. | none detected |
| EV-6502 | Um, I would give it longer than that. Give it a, an hour or so. | I would give it longer than that. Give it an hour or so. | none detected |
| EV-6557 | And um, I just felt like I, I needed to call you guys and needed to get some, some more information about what could be going on. | And I just felt like I needed to call you guys and needed to get some more information about what could be going on. | none detected |
| EV-6602 | I think probably do have what we call a simple UTI or urinary tract infection, bladder infection, um, which can be readily treated. So, I'll give you a course of antibiotics today. | I think we probably do have what we call a simple UTI or a urinary tract infection, bladder infection, which can be readily treated. So I'll give you a course of antibiotics today. | none detected |
| EV-6618 | alright thank you for calling and have a great day | All right, thank you for calling. Have a great day. | none detected |
| EV-6960 | am i going to need to bring an umbrella to work today | I'm going to need to bring an umbrella to work today. | none detected |
| EV-7409 | My boss told me that I could go home early or take a long lunch break another day. I decided to wait and take a long lunch break another time. | My boss told me that I could go home early to take a long lunch break another day. I decided to wait and take a long lunch break another time. | none detected |
| EV-7443 | i have ordered you a replacement debit card is there anything else i can help you with | I've ordered your replacement debit card. Is there anything else I can help you with? | none detected |
| EV-7690 | Alright then, OK. Take care then. All the best. | All right then. Okay. Take care then. All the best. | none detected |
| EV-7757 | that's very strenuous because your thumb is slightly up so they came up with something like this curled up so here | that's very strenuous because your thumb is slightly up so they come up with something like this curved up so here | none detected |
| EV-8062 | um this is this image will give you a little bit of an impression about um the look and feel that um the remote should have | This image will give you a little bit of an impression about the look and feel that the remote should have. | none detected |
| EV-8630 | so i think what we need to do is perhaps to synchronise the final the the launch of a user tested device with some special event | So I think what we need to do is perhaps to synchronize the launch of a user-tested device with some special event. | none detected |
| EV-8727 | Yeah, so again it's the left side, it's kind of like around my left ear, and kind of, along here. So like, my jawline's just all feeling a bit numb round here. | Yeah, so again it's the left side, it's kind of like around my left ear and kind of along here, so like my jawline is just all being a bit numb around here. | none detected |
| EV-8769 | and they have to wait for the right season to do | And you have to wait for the right season to do those. | none detected |
| EV-8971 | Yes, I was taking a shower when you called. I saw that you called and I tried to call you back, but I think your phone was off. | Yes, I was taking a shower when you called. I saw that you called and tried to call you back, but I think your phone was off. | none detected |
| EV-9018 | google i need the living room vacuumed can you turn on the vacuum cleaner | Google, I need a living room vacuum. Can you turn on the vacuum cleaner? | none detected |
| EV-9209 | In the middle, OK. And any other symptoms along that, with that? Have you had any nausea or any vomiting? | in the middle. Okay, and any other symptoms along that with that? Have you had any nausea and vomiting? | none detected |
| EV-9462 | replace card and which card would you like to replace | Place card and which card would you like to replace? | none detected |
| EV-9953 | All right. Did you have any questions that you wanted to ask me? | Alright, did you have any questions that you wanted to ask me? | none detected |

### small_hf: 41 câu có lỗi normalized

| ID | Reference | Hypothesis | Protected difference (posthoc) |
|---|---|---|---|
| EV-0333 | and my ears are buzzing a bit. I'm just, they're feeling a bit off. | and my ears are buzzing a bit, I'm just feeling a bit off. | none detected |
| EV-0523 | I'm sorry to hear that. Um, how old was he when that happened? | Sorry to hear that. How does it when that happens? | none detected |
| EV-0894 | OK, all right. And just moving on swiftly sir. Do you have any allergies at all? | Okay, all right. I'm just moving on swiftly sir. Do you have any allergies at all? | none detected |
| EV-1173 | am i going to have to worry about the snowplows burying my car in the driveway tuesday | Am I going to have to worry about a snow post bearing my car in the driveway to stay? | none detected |
| EV-1212 | Okay. And is the pain, is that, is it there all the time, or does it come and go? | Okay, and is it pain, is it there all the time or does it come and go? | none detected |
| EV-1229 | so we will send your payment to fossil gas is there anything else i can help you with | So we will send your payment to follow gas. Is there anything else that can help you with? | none detected |
| EV-1829 | no that's all i needed help with thank you very much for your help | Notice all needed help with. Thank you very much for your help. | first:no |
| EV-1863 | will the temperature stay about the same or is it going to get colder | with the temperature stay about the same or is it going to get colder? | none detected |
| EV-2177 | sure shame fair day and uh how long did you have mongey for | Yeah, shame. Fair day. And how long do you have the monkey for? | none detected |
| EV-2364 | the biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier uh remote controls are often lost in the room | The biggest frustrations that people found with regard to personal preferences were something that you mentioned earlier. Remote controls are often lost in the room. | none detected |
| EV-2390 | i need you to put walk the dog on my list to do | I need you to put the dog on my list to do. | none detected |
| EV-2476 | Um, work's kind of, um, a bit stressful at the moment. I've got a lot going on. | Work's kind of a bit stressful in the moment. I've got a lot going on. | none detected |
| EV-4177 | hi my name is michael wilson um i would like to transfer money between my accounts | Hi, my name is Michael Wilkins. I would like to transfer money between my accounts. | none detected |
| EV-4250 | OK, right, and with the diarrhea, how many times a day are you going to the toilet? | Okay, right. And with the diarrhea, how many times a day are you going to the toilet? | none detected |
| EV-4321 | okay um i'm going to read out the text now so we will understand what we are supposed to do | Okay, I'm going to read out the text now so we understand what you're supposed to do. | none detected |
| EV-4505 | surprising to me is like uh people give a lot of attention to modifying mobile phones like a real want to see a new launch or something like that | surprising thing is like people give a lot of attention to modifying mobile phones like every month you see a new launch or something like that | none detected |
| EV-5455 | Now, do you have those these symptoms right now or have they gone off now? | Now, do you have these symptoms right now or have they gone off now? | none detected |
| EV-5482 | Right, OK. Do you remember what you were doing at the time? | Right, okay. Do you remember what you were doing at the time? | none detected |
| EV-5600 | yeah i want some quality time quality time with my friends outside like it's it's really nice but we will see | Yeah, I want to some quality time with my friends and outside like it's really nice. But we will see. | none detected |
| EV-5674 | can i get a recap of what is going on with sprint right now | Can you get a recap of what is going on with the sprint right now? | none detected |
| EV-5761 | Have you measured it recently, either with an iWatch or something similar? | Have you measured it recently, either with an eye watch or something similar? | none detected |
| EV-6239 | OK. That's perfect. Thank you so much for your help. | Okay, that's perfect. Thank you so much for your help. | none detected |
| EV-6426 | easy to use has always has become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use | Easy to use has always become another major interest that with the whiteboard we can see that it's a remote that's easy to use. | none detected |
| EV-6502 | Um, I would give it longer than that. Give it a, an hour or so. | I would give it longer than that, give it an hour or so. | none detected |
| EV-6557 | And um, I just felt like I, I needed to call you guys and needed to get some, some more information about what could be going on. | And I just felt like I needed to call you guys and needed to get some more information about what could be going on. | none detected |
| EV-6602 | I think probably do have what we call a simple UTI or urinary tract infection, bladder infection, um, which can be readily treated. So, I'll give you a course of antibiotics today. | I think we probably do have what we call a simple UTI or a urinary tract infection, bladder infection, which can be readily treated. So I'll give you a course of antibiotics today. | none detected |
| EV-6618 | alright thank you for calling and have a great day | All right, thank you for calling. Have a great day. | none detected |
| EV-6960 | am i going to need to bring an umbrella to work today | I'm going to need to bring an umbrella to work today. | none detected |
| EV-7409 | My boss told me that I could go home early or take a long lunch break another day. I decided to wait and take a long lunch break another time. | My boss told me that I could go home early to take a long lunch break another day. I decided to wait and take a long lunch break another time. | none detected |
| EV-7443 | i have ordered you a replacement debit card is there anything else i can help you with | I've ordered your replacement debit card. Is there anything else I can help you with? | none detected |
| EV-7690 | Alright then, OK. Take care then. All the best. | All right then. Okay, take care then. All the best. | none detected |
| EV-7757 | that's very strenuous because your thumb is slightly up so they came up with something like this curled up so here | that's very strenuous because your thumb is slightly up so they come up with something like this curved up so here | none detected |
| EV-8062 | um this is this image will give you a little bit of an impression about um the look and feel that um the remote should have | This image will give you a little bit of an impression about the look and feel that the remote should have. | none detected |
| EV-8630 | so i think what we need to do is perhaps to synchronise the final the the launch of a user tested device with some special event | So I think what we need to do is perhaps to synchronize the launch of a user-tested device with some special event. | none detected |
| EV-8727 | Yeah, so again it's the left side, it's kind of like around my left ear, and kind of, along here. So like, my jawline's just all feeling a bit numb round here. | Yeah, so again it's the left side, it's kind of like around my left ear and kind of along here, so like my jawline is just all being a bit numb around here. | none detected |
| EV-8769 | and they have to wait for the right season to do | And you have to wait for the right season to do those. | none detected |
| EV-8971 | Yes, I was taking a shower when you called. I saw that you called and I tried to call you back, but I think your phone was off. | Yes, I was taking a shower when you called. I saw that you called and tried to call you back, but I think your phone was off. | none detected |
| EV-9018 | google i need the living room vacuumed can you turn on the vacuum cleaner | Google, I need a living room vacuum. Can you turn on the vacuum cleaner? | none detected |
| EV-9209 | In the middle, OK. And any other symptoms along that, with that? Have you had any nausea or any vomiting? | in the middle. Okay, and any other symptoms along that with that? Have you had any nausea and vomiting? | none detected |
| EV-9462 | replace card and which card would you like to replace | Place card and which card would you like to replace? | none detected |
| EV-9953 | All right. Did you have any questions that you wanted to ask me? | Alright, did you have any questions that you wanted to ask me? | none detected |

### medium: 35 câu có lỗi normalized

| ID | Reference | Hypothesis | Protected difference (posthoc) |
|---|---|---|---|
| EV-0333 | and my ears are buzzing a bit. I'm just, they're feeling a bit off. | and my ears are buzzing a bit, I'm just feeling a bit off. | none detected |
| EV-0523 | I'm sorry to hear that. Um, how old was he when that happened? | How old was he when that happened? | none detected |
| EV-0894 | OK, all right. And just moving on swiftly sir. Do you have any allergies at all? | Okay, all right, I'm just moving on swiftly sir. Do you have any allergies at all? | none detected |
| EV-1173 | am i going to have to worry about the snowplows burying my car in the driveway tuesday | Am I going to have to worry about the snow poles burying my car in the driveway Tuesday? | none detected |
| EV-1212 | Okay. And is the pain, is that, is it there all the time, or does it come and go? | Okay, and there's a pain is that is it there all the time or does it come and go? | none detected |
| EV-2177 | sure shame fair day and uh how long did you have mongey for | Yeah, shame. Fair day. And how long do you have monkey for? | none detected |
| EV-2364 | the biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier uh remote controls are often lost in the room | The biggest frustrations that people found with regard to personal preferences were something that you mentioned earlier, remote controls are often lost in the room. | none detected |
| EV-2476 | Um, work's kind of, um, a bit stressful at the moment. I've got a lot going on. | Work's kind of a bit stressful in the moment. I've got a lot going on. | none detected |
| EV-4250 | OK, right, and with the diarrhea, how many times a day are you going to the toilet? | Okay, right. And with the diarrhea, how many times a day are you going to the toilet? | none detected |
| EV-4321 | okay um i'm going to read out the text now so we will understand what we are supposed to do | Okay, I'm going to read out the text now so we understand what you're supposed to do. | none detected |
| EV-4505 | surprising to me is like uh people give a lot of attention to modifying mobile phones like a real want to see a new launch or something like that | Surprising thing is like people give a lot of attention to modifying mobile phones like every month you see a new launch or something like that | none detected |
| EV-5455 | Now, do you have those these symptoms right now or have they gone off now? | Now, do you have these symptoms right now or have they gone off now? | none detected |
| EV-5482 | Right, OK. Do you remember what you were doing at the time? | Right, okay. Do you remember what you were doing at the time? | none detected |
| EV-5600 | yeah i want some quality time quality time with my friends outside like it's it's really nice but we will see | Yeah, I want to spend quality time with my friends and outside it's really nice. But we will see. | none detected |
| EV-5674 | can i get a recap of what is going on with sprint right now | Can I get a recap of what is going on with the sprench right now? | none detected |
| EV-6239 | OK. That's perfect. Thank you so much for your help. | Okay, that's perfect. Thank you so much for your help. | none detected |
| EV-6426 | easy to use has always has become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use | Easy to use has become another major interest. With the whiteboard we can see that it's a remote that's easy to use. | none detected |
| EV-6502 | Um, I would give it longer than that. Give it a, an hour or so. | I would give it longer than that. Give it an hour or so. | none detected |
| EV-6557 | And um, I just felt like I, I needed to call you guys and needed to get some, some more information about what could be going on. | I just felt like I needed to call you guys and needed to get some more information about what could be going on. | none detected |
| EV-6602 | I think probably do have what we call a simple UTI or urinary tract infection, bladder infection, um, which can be readily treated. So, I'll give you a course of antibiotics today. | I think we probably do have what we call a simple UTI or urinary tract infection, bladder infection, which can be readily treated, so I'll give you a course of antibiotics today. | none detected |
| EV-6618 | alright thank you for calling and have a great day | All right, thank you for calling. Have a great day. | none detected |
| EV-6960 | am i going to need to bring an umbrella to work today | And I'm going to need to bring an opera singer to work today. | none detected |
| EV-7409 | My boss told me that I could go home early or take a long lunch break another day. I decided to wait and take a long lunch break another time. | My boss told me that I could go home early to take a long lunch break another day. I decided to wait and take a long lunch break another time. | none detected |
| EV-7443 | i have ordered you a replacement debit card is there anything else i can help you with | I've ordered your replacement debit card. Is there anything else I can help you with? | none detected |
| EV-7690 | Alright then, OK. Take care then. All the best. | Alright then, okay, take care then, all the best. | none detected |
| EV-7757 | that's very strenuous because your thumb is slightly up so they came up with something like this curled up so here | that's very strenuous because your thumb is slightly up so they come up with something like this, curved up, so here | none detected |
| EV-7818 | hi my name is miss james johnson and i would like to know my account balance | Hi, my name is Ms. James Johnson and I would like to know my account balance. | none detected |
| EV-8062 | um this is this image will give you a little bit of an impression about um the look and feel that um the remote should have | This image will give you a little bit of an impression about the look and feel that the remote should have. | none detected |
| EV-8630 | so i think what we need to do is perhaps to synchronise the final the the launch of a user tested device with some special event | So I think what we need to do is perhaps to synchronize the launch of a user-tested device with some special event. | none detected |
| EV-8727 | Yeah, so again it's the left side, it's kind of like around my left ear, and kind of, along here. So like, my jawline's just all feeling a bit numb round here. | Yeah, so again, it's the left side. It's kind of like around my left ear and kind of along here. So like my jawline is just a little bit numb around here. | none detected |
| EV-8769 | and they have to wait for the right season to do | And you have to wait for the right season to do those. | none detected |
| EV-8971 | Yes, I was taking a shower when you called. I saw that you called and I tried to call you back, but I think your phone was off. | Yes, I was taking a shower when you called. I saw that you called and tried to call you back, but I think your phone was off. | none detected |
| EV-9018 | google i need the living room vacuumed can you turn on the vacuum cleaner | Google, I need a living room vacuum. Can you turn on the vacuum cleaner? | none detected |
| EV-9209 | In the middle, OK. And any other symptoms along that, with that? Have you had any nausea or any vomiting? | In the middle. Okay. And any other symptoms along that with that? Have you had any nausea and vomiting? | none detected |
| EV-9462 | replace card and which card would you like to replace | Place card and which card would you like to replace | none detected |

### turbo: 36 câu có lỗi normalized

| ID | Reference | Hypothesis | Protected difference (posthoc) |
|---|---|---|---|
| EV-0250 | okey doke so we will send your payment to fossil gas is there anything else i can help you with today | Okie doke. So we will send your payment to Fossil Gas. Is there anything else I can help you with today? | none detected |
| EV-0333 | and my ears are buzzing a bit. I'm just, they're feeling a bit off. | and my ears are buzzing a bit, I'm just feeling a bit off. | none detected |
| EV-0523 | I'm sorry to hear that. Um, how old was he when that happened? | Sorry to hear that. How old was he when that happened? | none detected |
| EV-0894 | OK, all right. And just moving on swiftly sir. Do you have any allergies at all? | Okay, all right. I'm just moving on swiftly, sir. Do you have any allergies at all? | none detected |
| EV-1173 | am i going to have to worry about the snowplows burying my car in the driveway tuesday | Am I going to have to worry about the snow poles burying my car in the driveway to stay? | none detected |
| EV-1212 | Okay. And is the pain, is that, is it there all the time, or does it come and go? | Okay, and there's a pain is that is it there all the time or does it come and go? | none detected |
| EV-2177 | sure shame fair day and uh how long did you have mongey for | Yeah, shame. And how long did you have the monkey for? | none detected |
| EV-2364 | the biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier uh remote controls are often lost in the room | The biggest frustrations that people found with regard to personal preferences were something that you mentioned earlier. Remote controls are often lost in the room. | none detected |
| EV-4250 | OK, right, and with the diarrhea, how many times a day are you going to the toilet? | Okay, right. And with the diarrhea, how many times a day are you going to the toilet? | none detected |
| EV-4321 | okay um i'm going to read out the text now so we will understand what we are supposed to do | Okay, I'm going to read out the text now so we understand what you're supposed to do. | none detected |
| EV-4505 | surprising to me is like uh people give a lot of attention to modifying mobile phones like a real want to see a new launch or something like that | surprising thing is like people give a lot of attention to modifying mobile phones like every month you see a new launch or something like that | none detected |
| EV-5455 | Now, do you have those these symptoms right now or have they gone off now? | Now, do you have these symptoms right now or have they gone off now? | none detected |
| EV-5482 | Right, OK. Do you remember what you were doing at the time? | Right, okay. Do you remember what you were doing at the time? | none detected |
| EV-5600 | yeah i want some quality time quality time with my friends outside like it's it's really nice but we will see | Yeah, I want some quality time with my friends and outside. It's really nice. But we will see. | none detected |
| EV-5674 | can i get a recap of what is going on with sprint right now | Can I get a recap of what is going on with French right now? | none detected |
| EV-6239 | OK. That's perfect. Thank you so much for your help. | Okay, perfect. Thank you so much for your help. | none detected |
| EV-6426 | easy to use has always has become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use | Easy to use has always become another major interest that with the whiteboard we can see that it's a remote that's easy to use. | none detected |
| EV-6502 | Um, I would give it longer than that. Give it a, an hour or so. | I would give it longer than that. Give it an hour or so. | none detected |
| EV-6557 | And um, I just felt like I, I needed to call you guys and needed to get some, some more information about what could be going on. | And I just felt like I needed to call you guys and needed to get some more information about what could be going on. | none detected |
| EV-6602 | I think probably do have what we call a simple UTI or urinary tract infection, bladder infection, um, which can be readily treated. So, I'll give you a course of antibiotics today. | I think we probably do have what we call a simple UTI or urinary tract infection, bladder infection, which can be readily treated. So I'll give you a course of antibiotics today. | none detected |
| EV-6618 | alright thank you for calling and have a great day | All right. Thank you for calling. Have a great day. | none detected |
| EV-6960 | am i going to need to bring an umbrella to work today | And I'm going to lead your brain and our brother to work today. | none detected |
| EV-7409 | My boss told me that I could go home early or take a long lunch break another day. I decided to wait and take a long lunch break another time. | My boss told me that I could go home early to take a long lunch break another day. I decided to wait and take a long lunch break another time. | none detected |
| EV-7443 | i have ordered you a replacement debit card is there anything else i can help you with | I've ordered your replacement debit card. Is there anything else I can help you with? | none detected |
| EV-7690 | Alright then, OK. Take care then. All the best. | All right then. Okay. Take care then. All the best. | none detected |
| EV-7757 | that's very strenuous because your thumb is slightly up so they came up with something like this curled up so here | that's very strenuous because your thumb is slightly up so they come up with something like this curved up so here | none detected |
| EV-7818 | hi my name is miss james johnson and i would like to know my account balance | Hi, my name is Ms. James Johnson and I would like to know my account balance. | none detected |
| EV-8062 | um this is this image will give you a little bit of an impression about um the look and feel that um the remote should have | This image will give you a little bit of an impression about the look and feel that the remote should have. | none detected |
| EV-8596 | quiet mode on until i am home from work | Quiet board on until I am home from work. | none detected |
| EV-8630 | so i think what we need to do is perhaps to synchronise the final the the launch of a user tested device with some special event | So I think what we need to do is perhaps to synchronize the launch of a user-tested device with some special event. | none detected |
| EV-8727 | Yeah, so again it's the left side, it's kind of like around my left ear, and kind of, along here. So like, my jawline's just all feeling a bit numb round here. | Yeah, so again, it's the left side. It's kind of like around my left ear and kind of along here. So like my jawline is just all feeling a bit numb around here. | none detected |
| EV-8769 | and they have to wait for the right season to do | And you have to wait for the right season to do those things. | none detected |
| EV-8971 | Yes, I was taking a shower when you called. I saw that you called and I tried to call you back, but I think your phone was off. | Yes, I was taking a shower when you called. I saw that you called and tried to call you back, but I think your phone was off. | none detected |
| EV-9018 | google i need the living room vacuumed can you turn on the vacuum cleaner | Google, I need a living room vacuumed. Can you turn on the vacuum cleaner? | none detected |
| EV-9209 | In the middle, OK. And any other symptoms along that, with that? Have you had any nausea or any vomiting? | in the middle. Okay, and any other symptoms along with that? Have you had any nausea and vomiting? | none detected |
| EV-9953 | All right. Did you have any questions that you wanted to ask me? | Alright, did you have any questions that you wanted to ask me? | none detected |

### parakeet: 27 câu có lỗi normalized

| ID | Reference | Hypothesis | Protected difference (posthoc) |
|---|---|---|---|
| EV-0250 | okey doke so we will send your payment to fossil gas is there anything else i can help you with today | Oh okie doke. So we will send your payment to fossil gas. Is there anything else I can help you with today? | none detected |
| EV-0333 | and my ears are buzzing a bit. I'm just, they're feeling a bit off. | And my ears are buzzing a bit. I'm just feeling a bit off. | none detected |
| EV-0523 | I'm sorry to hear that. Um, how old was he when that happened? | Sorry to get that. Um how old how old is he when that happens? | none detected |
| EV-0894 | OK, all right. And just moving on swiftly sir. Do you have any allergies at all? | Okay, all right. And just moving on swiftly, sir. Do you have any allergies at all? | none detected |
| EV-1173 | am i going to have to worry about the snowplows burying my car in the driveway tuesday | Am I going to have to worry about the snow post burying my car in the driveway Tuesday? | none detected |
| EV-2177 | sure shame fair day and uh how long did you have mongey for | Sure, shame. Third day. And uh how long did you have the monkey for? | none detected |
| EV-2364 | the biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier uh remote controls are often lost in the room | Biggest frustrations that people found with regard to personal preferences were um something that you you mentioned earlier. Uh remote controls are often lost in the room. | none detected |
| EV-4250 | OK, right, and with the diarrhea, how many times a day are you going to the toilet? | Okay, right. And with the diarrhea, how many times a day are you going to the toilet? | none detected |
| EV-4321 | okay um i'm going to read out the text now so we will understand what we are supposed to do | Okay, um I'm going to read out the tax now so we would understand what we are supposed to do. | none detected |
| EV-4505 | surprising to me is like uh people give a lot of attention to modifying mobile phones like a real want to see a new launch or something like that | surprising thing is like uh people give a lot of attention to modifying mobile phones. Like every t month you see a new launch or something like that. | none detected |
| EV-5482 | Right, OK. Do you remember what you were doing at the time? | Right. Okay. Uh do you remember what you were doing at the time? | none detected |
| EV-5600 | yeah i want some quality time quality time with my friends outside like it's it's really nice but we will see | Yeah, I I want to some quality time quality time with my friends and outside like it's it's it's really nice. But we will see. | none detected |
| EV-5674 | can i get a recap of what is going on with sprint right now | Can I get a recap of what is going on with the sprint right now? | none detected |
| EV-5761 | Have you measured it recently, either with an iWatch or something similar? | Have you measured it recently, either with an eye watch or something similar? | none detected |
| EV-6239 | OK. That's perfect. Thank you so much for your help. | Okay, that's perfect, thank you so much for your help. | none detected |
| EV-6426 | easy to use has always has become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use | Easy to use has always become has become another major interest that uh with the whiteboard we can see that it's a remote that's easy to use. | none detected |
| EV-6602 | I think probably do have what we call a simple UTI or urinary tract infection, bladder infection, um, which can be readily treated. So, I'll give you a course of antibiotics today. | I think we probably do have what we call a simple UTI or a urinary tract infection or bladder infection um which can be readily treated. So I'll give you a course of antibiotics today. | none detected |
| EV-6618 | alright thank you for calling and have a great day | All right. Thank you for calling. Have a great day. | none detected |
| EV-6960 | am i going to need to bring an umbrella to work today | And I'm going to need to bring an umbrella to work today. | none detected |
| EV-7409 | My boss told me that I could go home early or take a long lunch break another day. I decided to wait and take a long lunch break another time. | My boss told me that I could go home early to take a long lunch break another day. I decided to wait and take a long lunch break another time. | none detected |
| EV-7443 | i have ordered you a replacement debit card is there anything else i can help you with | I've ordered your replacement debit card. Is there anything else I can help you with? | none detected |
| EV-7690 | Alright then, OK. Take care then. All the best. | All right then. Okay. Take care then. All the best. | none detected |
| EV-7757 | that's very strenuous because your thumb is slightly up so they came up with something like this curled up so here | That's very strenuous because your thumb is slightly up. So they come up with something like this, curved up. So here | none detected |
| EV-8727 | Yeah, so again it's the left side, it's kind of like around my left ear, and kind of, along here. So like, my jawline's just all feeling a bit numb round here. | Yeah, so again it's the left side, it's kind of like around my left ear and kind of along here, so like my jawline is just all feeling a bit numb around here. | none detected |
| EV-8769 | and they have to wait for the right season to do | And they have to wait for the right season to do those. | none detected |
| EV-8971 | Yes, I was taking a shower when you called. I saw that you called and I tried to call you back, but I think your phone was off. | Yes, I was taking a shower when you called. I saw that you called and tried to call you back, but I think your phone was off. | none detected |
| EV-9209 | In the middle, OK. And any other symptoms along that, with that? Have you had any nausea or any vomiting? | In the middle. Okay. And any other symptoms along that with that? Have you had any nausea and vomiting? | none detected |

`ACCEPT` chỉ có nghĩa không phát hiện protected risk trong hypothesis hiện tại. Nó không xác nhận transcript đúng. Negation bị ASR bỏ hoàn toàn có thể biến thành NORMAL words và lọt guard; hai pass cùng bỏ negation cũng không thể được phát hiện bằng agreement. Chi tiết raw-only lỗi nằm trong JSON summary.

## Cross-model diagnostic riêng

So Whisper Small và Parakeet trên cùng WAV hash; đây **không phải** retry cùng model với hai decoding configuration. Không đưa số này vào bảng single-pass và không coi là confidence xác suất.

| Diagnostic | N | ACCEPT | RETRY_ASR | ASK_REPEAT |
|---|---:|---:|---:|---:|
| small_vs_parakeet | 100 | 81 | 0 | 19 |

Khác format số (“one”/“1”), contraction, hoặc tên riêng có thể tạo disagreement bảo thủ. Các token LOW khác nhau không tự tạo ASK_REPEAT. Một shared VAD skip được giữ và đánh dấu riêng.

## Tái lập và giới hạn

Từ repo root: `python3 -m experiments.asr_reliability_guard.evaluate`.

- `results/evaluation-*.jsonl`: toàn bộ 100 debug decisions/run và cross-model alignment.
- `results/evaluation-summary.json`: counts, danh sách lỗi lọt ACCEPT, false alarm, input SHA256.
- Không học từ BTC reference. Không có user confirmation thật trong dataset nên evaluation dùng personalization bằng 0.
- Không có audio/model local tương ứng để rerun same-model greedy/beam; đây là offline replay trên evidence đã có.
- Threshold/weight là prototype chưa calibration. Chưa đo selective retry latency, false reject trên acoustic holdout hoặc unsafe permission.
- Bước tiếp: thu word evidence/provenance hoặc paired decode cùng audio; split calibration/holdout theo speaker/site; review negation/number/ID mistakes và tối ưu false alarm cùng lỗi protected lọt ACCEPT.

### Input SHA256

| File | SHA256 |
|---|---|
| output/phase3/en-asr-20261113/evidence/inputs/en100.jsonl | `c593cc898ad28e4e0c26e45f47b7bc2880208e57466ef0ccc1a2a9ac2755f132` |
| output/phase3/en-asr-20261113/evidence/parent/comparison.json | `c48f700e5c1fdcb6d3abb20cff1d36f9bf1699ec91ffe67139b08b69e7a4e2b5` |
| experiments/asr_reliability_guard/scoring.py | `508a524e02c9a59a4d74e2f7632457f4b16adfd679ef0040d00b56d6e96a414e` |
| experiments/asr_reliability_guard/scoring_provenance.json | `81470749d96c735e1b0137287b40325cc53608b0b3967deb7760cfb17b55de91` |
| output/phase3/en-asr-20261113/evidence/parent/baseline-app.jsonl | `936e5e358aeb183ab94cf7c851c3c7e2b5b9efcb5a11d33f2e4ba9b8edf500dc` |
| output/phase3/en-asr-20261113/evidence/whisper/whisper-small-bf16-en100.jsonl | `dd3cd6ac290ae6bed1df091313934b6b2588992a117ef6f50a000685ffcb25d3` |
| output/phase3/en-asr-20261113/evidence/whisper/whisper-medium-bf16-en100.jsonl | `2755cb9f9a525e02b1ae5ccab49cf434c6411d383b8a909c6c53753cbc6dccc3` |
| output/phase3/en-asr-20261113/evidence/whisper/whisper-large-v3-turbo-bf16-en100.jsonl | `88f5e81e5f499102f632e0b10af037649643652b13246fb8b0d8cb5200c3ea81` |
| output/phase3/en-asr-20261113/evidence/parakeet/full100.jsonl | `a17c8ac74dfc0ace017acef6d29d36a1abd41e0625225de35065604f05470223` |
| experiments/asr_reliability_guard/config/importance_en.json | `7b2455e9ddcaca57d58ab85fccd4870a973ed4a199b0e17664168151d4e01323` |
| experiments/asr_reliability_guard/config/negation_en.json | `d0bbfd7724ffb405cef02c94b83001bd5b3c1dbdb6cf790706e9739a491cb90f` |
| experiments/asr_reliability_guard/config/policy.json | `dc128f1477b1c3bf294afb15695ede10cc6944f13c3689d917268bde3e34a698` |
