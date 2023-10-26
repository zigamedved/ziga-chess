# Project handling

# How to run

## JAVA server:
- go to root directory and run
- `docker build -t image-name --build-arg PREFIX=/v1/chess .`
- `docker run --rm -p 8080:8080 your-image-name` // endpoint available at http://0.0.0.0:8080+prefix/position

## PYTHON server:
- go to root directory and run
- `docker build -t image-name --build-arg PREFIX=/v1/chess .`
- `docker run --rm -p 8081:8081 your-image-name` // endpoint available at http://0.0.0.0:8081+prefix/analyse

# R&D
- improve game attributes in main.py, build index and update the indexedFiles directory in chess-maven project

# Request example
- method `POST`
- body:
`{
	"FEN": "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"
}`
- headers: `Authorization Basic ...`

- example response:
`Analysed lines of input game:

PV1: 
[Result "*"]
[FEN "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"]
[SetUp "1"]

25... Rfe8 26. Rae1 Kf8 27. Rxe8+ Rxe8 28. Rxe8+ Kxe8 29. Kg2 *

PV2: 
[Result "*"]
[FEN "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"]
[SetUp "1"]

25... f6 26. Rae1 Rf7 27. Re8+ Rf8 28. R8e4 Rab8 29. b3 *


Similar games:


Game #0

White: Christiansen,Johan-Sebastian
Black: Guliev,L
Result: 1-0
endgameFEN: r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25
name: Game#894776.txt
PGN: 1. e4 c5 2. Nf3 d6 3. Nc3 a6 4. g3 e6 5. Bg2 Nf6 6. e5 dxe5 7. Nxe5 Be7 8. a4 O-O 9. O-O Nbd7 10. Nc4 Nb6 11. Nxb6 Qxb6 12. a5 Qc7 13. d3 Bd7 14. Bf4 e5 15. Bg5 Bc6 16. Bxc6 Qxc6 17. Qe2 Qe6 18. Rfe1 Qh3 19. Bxf6 Bxf6 20. Qf3 Qd7 21. Nd5 Qc6 22. Rxe5 Bd8 23. Ne7+ Bxe7 24. Qxc6 bxc6 25. Rxe7 c4 26. dxc4 Rfd8 27. Rae1 Kf8 28. Rc7 Rd6 29. Ree7 Rf6 30. Kg2 g6 31. Re3 Rd8 32. Rf3 Rxf3 33. Kxf3 Rd2 34. Rxc6 Rxc2 35. b4 Rb2 36. Rb6 Ke7 37. b5 axb5 38. cxb5 Rb3+ 39. Ke4 Kd7 40. Rb7+ Ke6 41. a6 f5+ 42. Kd4 1-0
PV1: [FEN "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"][SetUp "1"]25... Rfe8 26. Rae1 Kf8 27. Rxe8+ Rxe8 28. Rxe8+ *
PV2: [FEN "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"][SetUp "1"]25... f6 26. Rae1 Rf7 27. Re8+ Rf8 28. R8e4 *
score: 86.84861755371094


Game #1

White: Demuth,A
Black: Sagar,S
Result: 1/2-1/2
endgameFEN: r4rk1/pp3ppp/8/8/1P2R3/P5P1/5P1P/R5K1 b - - 0 24
name: Game#122214.txt
PGN: 1. d4 Nf6 2. c4 e6 3. g3 Bb4+ 4. Bd2 Qe7 5. Nf3 Bxd2+ 6. Qxd2 Nc6 7. b3 d5 8. Bg2 Ne4 9. Qd3 O-O 10. O-O Nb4 11. Qd1 c5 12. a3 Nc6 13. dxc5 Qxc5 14. cxd5 exd5 15. b4 Qd6 16. Qd3 Ne5 17. Nxe5 Qxe5 18. Nd2 Nc3 19. Rfe1 Bf5 20. e4 Nxe4 21. Nxe4 dxe4 22. Bxe4 Bxe4 23. Qxe4 Qxe4 24. Rxe4 f5 25. Re7 Rf7 26. Rae1 Kf8 27. Rxf7+ 1/2-1/2
PV1: [FEN "r4rk1/pp3ppp/8/8/1P2R3/P5P1/5P1P/R5K1 b - - 0 24"][SetUp "1"]24... f6 25. Rd1 Rae8 26. Rc4 Re7 27. h4 *
PV2: [FEN "r4rk1/pp3ppp/8/8/1P2R3/P5P1/5P1P/R5K1 b - - 0 24"][SetUp "1"]24... Rfe8 25. Rae1 Kf8 26. Rxe8+ Rxe8 27. Rc1 *
score: 63.97635269165039


Game #2

White: Kaasen,Tor Fredrik
Black: Gamback,B
Result: 1-0
endgameFEN: r3r1k1/p4ppp/2p5/2pp4/P7/3P4/1PP2PPP/R3R1K1 w - - 0 21
name: Game#776572.txt
PGN: 1. e4 c5 2. Nf3 d6 3. Bb5+ Bd7 4. Bxd7+ Qxd7 5. O-O Nf6 6. e5 dxe5 7. Nxe5 Qd6 8. Re1 Nc6 9. Nc4 Qd8 10. Nc3 e6 11. d3 Be7 12. Bf4 O-O 13. a4 Nd5 14. Nxd5 exd5 15. Ne5 Bf6 16. Qg4 Nxe5 17. Bxe5 Re8 18. Bxf6 Qxf6 19. Qd7 Qc6 20. Qxc6 bxc6 21. Kf1 Kf8 22. Ra3 Rab8 23. Rc3 Rxb2 24. Rxc5 Rc8 25. Re5 Rb4 26. a5 Rbb8 27. f4 a6 28. Kf2 Ra8 29. g4 h6 30. Kf3 Rab8 31. Ke3 Ra8 32. h4 Rab8 33. g5 hxg5 34. hxg5 g6 35. f5 gxf5 36. Kf4 Rb5 37. Rexd5 Rxa5 38. Rxf5 Rxc5 39. Rxc5 Ke7 40. Ke5 Rc7 41. d4 Kd7 42. c4 Ra7 43. Kf6 a5 44. Kxf7 a4 45. Re5 Kc8+ 46. Kf8 a3 47. Re8+ Kc7 48. Re7+ Kb8 49. Re1 Rd7 50. Rg1 Rxd4 51. g6 Rf4+ 52. Ke7 Re4+ 53. Kf6 a2 54. g7 Rg4 55. g8=Q+ Rxg8 56. Rxg8+ Kb7 57. Rg1 Kb6 58. Ra1 Kc5 59. Rxa2 Kxc4 60. Rc2+ Kd5 61. Kf5 c5 62. Rd2+ Kc4 63. Ke4 Kc3 64. Rd3+ Kc2 65. Rd5 c4 66. Rc5 c3 67. Kd4 Kb2 68. Rxc3 Ka2 69. Rh3 Kb2 70. Kc4 Ka2 71. Kb4 Kb2 72. Rh2+ Kc1 73. Kc3 Kd1 74. Ra2 Ke1 75. Kd3 Kf1 76. Ke3 Kg1 77. Kf3 Kh1 78. Kg3 Kg1 79. Ra1# 1-0
PV1: [FEN "r3r1k1/p4ppp/2p5/2pp4/P7/3P4/1PP2PPP/R3R1K1 w - - 0 21"][SetUp "1"]21. Rxe8+ Rxe8 22. Kf1 Rb8 23. Re1 f6 *
PV2: [FEN "r3r1k1/p4ppp/2p5/2pp4/P7/3P4/1PP2PPP/R3R1K1 w - - 0 21"][SetUp "1"]21. Kf1 Kf8 22. b3 Rab8 23. Rxe8+ Rxe8 *
score: 62.587223052978516


Game #3

White: Cvitan, Ognjen
Black: Yrjola, Jouni
Result: 1/2-1/2
endgameFEN: r4rk1/p1p2ppp/2pp4/8/2PP4/6PP/PP3P2/R4RK1 w - - 0 18
name: Game#52802.txt
PGN: 1. d4 Nf6 2. Nf3 e6 3. c4 Bb4+ 4. Bd2 Qe7 5. g3 Nc6 6. Nc3 Bxc3 7. Bxc3 Ne4 8. Qc2 Nxc3 9. Qxc3 O-O 10. Bg2 d6 11. O-O e5 12. e3 Bg4 13. h3 Bxf3 14. Bxf3 Qf6 15. Bxc6 exd4 16. Qxd4 Qxd4 17. exd4 bxc6 18. Rfe1 Rfe8 19. Kf1 Kf8 1/2-1/2
PV1: [FEN "r4rk1/p1p2ppp/2pp4/8/2PP4/6PP/PP3P2/R4RK1 w - - 0 18"][SetUp "1"]18. Rfe1 Rfe8 19. Rxe8+ Rxe8 20. Kf1 Kf8 *
PV2: [FEN "r4rk1/p1p2ppp/2pp4/8/2PP4/6PP/PP3P2/R4RK1 w - - 0 18"][SetUp "1"]18. b3 Rfe8 19. Rfe1 a5 20. Kf1 Kf8 *
score: 61.352664947509766


Game #4

White: Sedina,E
Black: Muzychuk,M
Result: 1/2-1/2
endgameFEN: r4rk1/p5pp/4R3/2pp4/8/2P5/PP3PPP/R5K1 b - - 0 22
name: Game#414004.txt
PGN: 1. e4 c5 2. Nf3 e6 3. c3 d5 4. exd5 exd5 5. d4 Nc6 6. Bb5 Bd6 7. dxc5 Bxc5 8. O-O Ne7 9. Nbd2 O-O 10. Nb3 Bb6 11. Re1 Qd6 12. Be3 Bxe3 13. Rxe3 Ng6 14. Bxc6 bxc6 15. Qd4 Nf4 16. Qe5 Qh6 17. Nc5 Ne6 18. Nxe6 Bxe6 19. Nd4 c5 20. Nxe6 fxe6 21. Qxe6+ Qxe6 22. Rxe6 Rab8 23. Re2 d4 24. cxd4 cxd4 25. Rd1 Rfd8 26. Rd3 a5 27. h4 Rd7 28. g3 h5 29. a3 Rb5 30. Kg2 g6 31. b3 Kf7 32. Re4 Rdb7 33. Rdxd4 Rxb3 34. Rf4+ Kg7 35. Rd6 Rf7 36. Rxf7+ Kxf7 37. a4 Rb4 38. Kf3 Rxa4 39. Ra6 Ra2 40. Ke3 Ra4 41. f3 1/2-1/2
PV1: [FEN "r4rk1/p5pp/4R3/2pp4/8/2P5/PP3PPP/R5K1 b - - 0 22"][SetUp "1"]22... Rae8 23. Rxe8 Rxe8 24. Kf1 Rb8 25. b3 *
PV2: [FEN "r4rk1/p5pp/4R3/2pp4/8/2P5/PP3PPP/R5K1 b - - 0 22"][SetUp "1"]22... Rfe8 23. Rxe8+ Rxe8 24. Kf1 Rb8 25. b3 *
score: 60.82029724121094


Game #5

White: Praggnanandhaa,R
Black: Gledura,B
Result: 1/2-1/2
endgameFEN: r4rk1/pp3ppp/2pp4/8/3PR3/8/PPP2PPP/R5K1 b - - 0 18
name: Game#234092.txt
PGN: 1. e4 e5 2. Nf3 Nc6 3. Nc3 Nf6 4. Bb5 Nd4 5. Nxd4 exd4 6. e5 dxc3 7. exf6 Qxf6 8. dxc3 Qe5+ 9. Be2 Bc5 10. O-O O-O 11. Re1 Qf6 12. Bf3 c6 13. Be3 d6 14. Bd4 Bxd4 15. Qxd4 Qxd4 16. cxd4 Bf5 17. Be4 Bxe4 18. Rxe4 Rfe8 19. Rae1 Kf8 20. Kf1 d5 21. R4e3 Rxe3 22. Rxe3 Re8 23. Ra3 a6 24. Rb3 Re7 25. f3 Ke8 26. Kf2 Kd8 27. Re3 Kd7 28. Rxe7+ Kxe7 29. Ke3 a5 30. c3 Ke6 1/2-1/2
PV1: [FEN "r4rk1/pp3ppp/2pp4/8/3PR3/8/PPP2PPP/R5K1 b - - 0 18"][SetUp "1"]18... Rfe8 19. Re3 Kf8 20. Rxe8+ Rxe8 21. Kf1 *
PV2: [FEN "r4rk1/pp3ppp/2pp4/8/3PR3/8/PPP2PPP/R5K1 b - - 0 18"][SetUp "1"]18... Rae8 19. Rae1 Rxe4 20. Rxe4 f6 21. Re7 *
score: 60.33699417114258


Game #6

White: Guseinov,G
Black: Kovalenko,I
Result: 1/2-1/2
endgameFEN: r3r1k1/5p2/p1p2p1p/P7/2p5/6P1/2P2P1P/R3R1K1 w - - 0 27
name: Game#734280.txt
PGN: 1. e4 c5 2. Nc3 Nc6 3. Nf3 e6 4. d4 cxd4 5. Nxd4 Nf6 6. g3 Bb4 7. Bg2 O-O 8. O-O h6 9. a3 Bxc3 10. bxc3 Qa5 11. a4 d5 12. exd5 exd5 13. Ba3 Re8 14. Qd3 Bg4 15. Rfb1 Ne5 16. Bb4 Qd8 17. Qb5 Qc8 18. Qc5 Nc6 19. Re1 Nxd4 20. Qxd4 Qd7 21. a5 a6 22. c4 dxc4 23. Qxd7 Bxd7 24. Bc3 Bc6 25. Bxc6 bxc6 26. Bxf6 gxf6 27. Rxe8+ Rxe8 28. Ra4 Re5 29. Rxc4 Rxa5 30. Rxc6 Kg7 1/2-1/2
PV1: [FEN "r3r1k1/5p2/p1p2p1p/P7/2p5/6P1/2P2P1P/R3R1K1 w - - 0 27"][SetUp "1"]27. Rxe8+ Rxe8 28. Ra4 Re4 29. Kg2 f5 *
PV2: [FEN "r3r1k1/5p2/p1p2p1p/P7/2p5/6P1/2P2P1P/R3R1K1 w - - 0 27"][SetUp "1"]27. Kg2 Rxe1 28. Rxe1 Rb8 29. Re4 Rb4 *
score: 57.66921615600586


Game #7

White: Labib,I
Black: Minasian,Art
Result: 1/2-1/2
endgameFEN: r4rk1/pp3ppp/8/3p4/8/5P2/PPP2P1P/R3R1K1 b - - 0 20
name: Game#287866.txt
PGN: 1. e4 e6 2. d4 d5 3. Nd2 Be7 4. Bd3 c5 5. dxc5 Nd7 6. exd5 exd5 7. Nb3 Ngf6 8. Be3 O-O 9. Nf3 Ne4 10. O-O Ndxc5 11. Nxc5 Nxc5 12. Nd4 Nxd3 13. Qxd3 Bd6 14. Rfe1 Qh4 15. Nf3 Qh5 16. Qd4 Bg4 17. Bf4 Bxf4 18. Qxf4 Bxf3 19. Qxf3 Qxf3 20. gxf3 Rfe8 21. Rxe8+ Rxe8 22. Rd1 Rd8 23. Rd4 Kf8 24. c4 Ke7 25. cxd5 Kd6 26. Kg2 Rc8 27. Rg4 g6 28. Rh4 h5 29. Rb4 b6 30. Kg3 Kxd5 31. Rf4 Rc7 32. h4 f5 33. Rb4 Rc2 34. Rb3 Kc4 35. Kg2 f4 36. Ra3 a5 37. Ra4+ Kc5 38. b3 Rb2 39. Rc4+ Kb5 40. a4+ Ka6 41. Rc3 Rb1 42. Kh2 Ka7 43. Kh3 Kb7 44. Kg2 Rb2 45. Kf1 b5 46. axb5 Kb6 47. Rc6+ Kxb5 48. Rxg6 Rxb3 49. Rg5+ Ka6 50. Rxh5 a4 51. Rh8 Rb7 52. Rd8 Ra7 53. Kg2 a3 54. Rd1 Kb5 55. Kh3 a2 56. Ra1 Kc4 57. Kg4 Kb3 58. Kxf4 Ra5 59. Kg4 Kb2 60. Rxa2+ Rxa2 61. h5 Kc3 62. h6 Kd4 63. h7 Ra8 64. Kf5 Rh8 65. Kg6 1/2-1/2
PV1: [FEN "r4rk1/pp3ppp/8/3p4/8/5P2/PPP2P1P/R3R1K1 b - - 0 20"][SetUp "1"]20... Rfc8 21. c3 Re8 22. Re3 Kf8 23. Rae1 *
PV2: [FEN "r4rk1/pp3ppp/8/3p4/8/5P2/PPP2P1P/R3R1K1 b - - 0 20"][SetUp "1"]20... Rfe8 21. Rxe8+ Rxe8 22. Rd1 Rd8 23. c4 *
score: 57.5941047668457


Game #8

White: Girya,O
Black: Ju Wenjun
Result: 1/2-1/2
endgameFEN: r4rk1/pp2Rpp1/4p2p/8/8/P3PP2/1P3P1P/5RK1 b - - 0 21
name: Game#525312.txt
PGN: 1. d4 Nf6 2. c4 e6 3. Nf3 d5 4. Nc3 Bb4 5. Bg5 h6 6. Bxf6 Qxf6 7. e3 O-O 8. Rc1 dxc4 9. Bxc4 c5 10. O-O cxd4 11. Ne4 Qe7 12. Nxd4 Bd7 13. Be2 Nc6 14. a3 Nxd4 15. Qxd4 Ba5 16. Qc5 Bd8 17. Qxe7 Bxe7 18. Rc7 Bc6 19. Rxe7 Bxe4 20. Bf3 Bxf3 21. gxf3 Rab8 22. Rc1 Rfe8 23. Rxe8+ Rxe8 24. Rc7 Rb8 25. f4 g6 26. Kg2 Kg7 27. b4 a6 28. Kf3 g5 29. Ke4 Kf6 30. h3 b5 31. Rc6 Ra8 32. Rb6 Ra7 33. f5 h5 34. fxe6 fxe6 35. f4 gxf4 36. Kxf4 Rc7 37. Rxa6 Rc4+ 38. Kf3 Rh4 39. Kg3 Rc4 40. Rb6 Rc3 41. Kf4 Rxa3 42. Rxb5 h4 43. Rb8 Rb3 44. b5 Rb4+ 45. Kf3 Ke5 46. b6 Kf6 47. b7 Ke5 48. Ke2 Ke4 49. Kd2 e5 50. Kc3 Rb1 51. Kc2 Rb6 52. Kd2 Rb5 53. Kc3 Kxe3 54. Kc4 Rb1 55. Kd5 e4 56. Re8 Rxb7 57. Rxe4+ Kf3 58. Rxh4 Kg3 59. Rh8 Rb4 60. Ke5 Rh4 61. Rxh4 Kxh4 62. Kf4 Kxh3 1/2-1/2
PV1: [FEN "r4rk1/pp2Rpp1/4p2p/8/8/P3PP2/1P3P1P/5RK1 b - - 0 21"][SetUp "1"]21... Rab8 22. Rd1 Rfe8 23. Rxe8+ Rxe8 24. Rd7 *
PV2: [FEN "r4rk1/pp2Rpp1/4p2p/8/8/P3PP2/1P3P1P/5RK1 b - - 0 21"][SetUp "1"]21... b5 22. Rc1 Rfe8 23. Rxe8+ Rxe8 24. Rc7 *
score: 57.3174934387207


Game #9

White: Laylo,D
Black: Morado,Jeth Romy
Result: 1/2-1/2
endgameFEN: r3r1k1/4Rp1p/p5p1/Pp6/1P4P1/8/5P1P/4R1K1 b - - 0 42
name: Game#534512.txt
PGN: 1. d4 Nf6 2. Nf3 e6 3. c4 b6 4. a3 Bb7 5. Nc3 Be7 6. d5 O-O 7. e4 exd5 8. cxd5 Re8 9. Bd3 c6 10. O-O cxd5 11. exd5 d6 12. Nd4 Nbd7 13. Bf4 Ne5 14. Bb5 Rf8 15. Nf5 Ng6 16. Bg3 a6 17. Bc6 Bxc6 18. dxc6 Qc8 19. Nd4 Ra7 20. Qb3 Qa8 21. Rfe1 Ne5 22. Nd5 Nxd5 23. Qxd5 Bf6 24. Rad1 Rc8 25. Nf5 Nxc6 26. Nxd6 Rd8 27. Rc1 Nd4 28. Qxa8 Raxa8 29. Kf1 Ne6 30. Rc2 g6 31. a4 Be7 32. Rc6 Bxd6 33. Bxd6 b5 34. a5 Nd4 35. Rb6 Nb3 36. Be7 Nd2+ 37. Kg1 Re8 38. Rb7 Nc4 39. b4 Nd6 40. Rd7 Nf5 41. g4 Nxe7 42. Rdxe7 Rxe7 43. Rxe7 Rc8 44. Re4 h6 45. Kg2 Rc3 46. h4 Kg7 47. g5 hxg5 48. hxg5 Rb3 49. f3 f5 50. Re7+ Kf8 51. Re6 Kf7 52. Rf6+ Kg7 53. Rxa6 Rxb4 54. Ra7+ Kf8 55. Rb7 Ra4 56. Rxb5 Ke7 57. Kg3 Kf7 58. Rb7+ Ke6 59. Rb6+ Ke5 60. a6 f4+ 61. Kg4 Ra1 62. a7 Rxa7 63. Rxg6 Ra1 64. Rb6 Rg1+ 65. Kh5 Rg3 66. Rb3 Kf5 67. Kh4 Kg6 68. Rb6+ Kf5 69. Rb5+ Kg6 70. Rb3 Kf5 71. Rc3 Kg6 72. Rc6+ Kf5 73. Rc5+ Kg6 74. Rd5 Rxf3 75. Kg4 Ra3 76. Rd6+ Kg7 77. Kh5 Rh3+ 78. Kg4 Ra3 79. Rb6 f3 80. Rf6 Ra7 81. Rxf3 Ra6 82. Kh5 Ra7 83. Rf6 Rb7 1/2-1/2
PV1: [FEN "r3r1k1/4Rp1p/p5p1/Pp6/1P4P1/8/5P1P/4R1K1 b - - 0 42"][SetUp "1"]42... Rxe7 43. Rxe7 Rc8 44. Re4 g5 45. Rd4 *
PV2: [FEN "r3r1k1/4Rp1p/p5p1/Pp6/1P4P1/8/5P1P/4R1K1 b - - 0 42"][SetUp "1"]42... Kf8 43. Rxe8+ Rxe8 44. Rxe8+ Kxe8 45. Kf1 *
score: 57.08833694458008


Game #10

White: Potocki,K
Black: Jedrusik,Adam
Result: 0-1
endgameFEN: r4rk1/1p3ppp/p1p5/8/2P5/1P6/PR3PPP/R5K1 b - - 0 19
name: Game#161582.txt
PGN: 1. c4 c6 2. d4 d5 3. Nf3 Nf6 4. e3 a6 5. b3 Bg4 6. Nbd2 e6 7. Be2 Bd6 8. Bb2 Nbd7 9. Qc2 O-O 10. O-O Qe7 11. Rfe1 e5 12. e4 dxe4 13. Nxe5 Bxe2 14. Rxe2 Nxe5 15. dxe5 Bxe5 16. Nxe4 Bxb2 17. Nxf6+ Qxf6 18. Qxb2 Qxb2 19. Rxb2 Rad8 20. Kf1 Rd7 21. c5 f6 22. b4 Rfd8 23. a4 Rd5 24. Rc1 Kf7 25. b5 axb5 26. axb5 Rd1+ 27. Rxd1 Rxd1+ 28. Ke2 Rc1 29. bxc6 bxc6 30. Rb7+ Kg6 31. g4 Rxc5 32. Rc7 Rc2+ 33. Ke3 c5 34. f4 Rxh2 35. Rxc5 h5 36. gxh5+ Rxh5 37. Rc7 Rh3+ 38. Kf2 Ra3 39. f5+ Kh6 40. Rc1 Rh3 41. Rc5 Kg5 42. Kg2 Rh4 43. Kg3 Ra4 44. Rc7 Ra3+ 45. Kg2 Kh6 46. Rc5 Re3 0-1
PV1: [FEN "r4rk1/1p3ppp/p1p5/8/2P5/1P6/PR3PPP/R5K1 b - - 0 19"][SetUp "1"]19... Rfd8 20. Re2 Rd7 21. Rae1 f6 22. h4 *
PV2: [FEN "r4rk1/1p3ppp/p1p5/8/2P5/1P6/PR3PPP/R5K1 b - - 0 19"][SetUp "1"]19... Rfe8 20. Kf1 Rad8 21. Re1 Kf8 22. Rxe8+ *
score: 56.89363098144531`

# Sources
- https://stockfishchess.org